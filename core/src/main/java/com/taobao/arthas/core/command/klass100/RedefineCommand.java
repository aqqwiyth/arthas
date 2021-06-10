package com.taobao.arthas.core.command.klass100;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.logger.LoggerCommand;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

/**
 * Redefine Classes.
 *
 * @author hengyunabc 2018-07-13
 * @see java.lang.instrument.Instrumentation#redefineClasses(ClassDefinition...)
 */
@Name("redefine")
@Summary("注意: Redefine后的类 reset/shutdown无法还原|  Redefine classes. @see Instrumentation#redefineClasses(ClassDefinition...)|")
@Description(Constants.EXAMPLE +
                "  redefine /tmp/Test.class\n" +
                "  redefine -c 327a647b /tmp/Test.class /tmp/Test\\$Inner.class \n" +
                "  redefine --classLoaderClass 'sun.misc.Launcher$AppClassLoader' /tmp/Test.class \n" +
                Constants.WIKI + Constants.WIKI_HOME + "redefine")
public class RedefineCommand extends AnnotatedCommand {
    private static final Logger logger = LoggerFactory.getLogger(RedefineCommand.class);
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    private String hashCode;
    private String classLoaderClass;

    private List<String> paths;

    // 类-字节码缓存
    protected final static Map<Class<?>/*Class*/, String /*bytes of Class*/> classBytesPathMap = new WeakHashMap<Class<?>, String>();

    @Option(shortName = "c", longName = "classloader")
    @Description("classLoader hashcode")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Option(longName = "classLoaderClass")
    @Description("The class name of the special class's classLoader.")
    public void setClassLoaderClass(String classLoaderClass) {
        this.classLoaderClass = classLoaderClass;
    }

    @Argument(argName = "classfilePaths", index = 0)
    @Description(".class file paths")
    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    @Override
    public void process(CommandProcess process) {
        RedefineModel redefineModel = new RedefineModel();
        Instrumentation inst = process.session().getInstrumentation();
        for (String path : paths) {
            File file = new File(path);
            if (!file.exists()) {
                process.end(-1, "file does not exist, path:" + path);
                return;
            }
            if (!file.isFile()) {
                process.end(-1, "not a normal file, path:" + path);
                return;
            }
            if (file.length() >= MAX_FILE_SIZE) {
                process.end(-1, "file size: " + file.length() + " >= " + MAX_FILE_SIZE + ", path: " + path);
                return;
            }
        }

        Map<String, byte[]> bytesMap = new HashMap<String, byte[]>();
        for (String path : paths) {
            RandomAccessFile f = null;
            try {
                f = new RandomAccessFile(path, "r");
                final byte[] bytes = new byte[(int) f.length()];
                f.readFully(bytes);

                final String clazzName = readClassName(bytes);

                bytesMap.put(clazzName, bytes);

            } catch (Exception e) {
                logger.warn("load class file failed: "+path, e);
                process.end(-1, "load class file failed: " +path+", error: " + e);
                return;
            } finally {
                if (f != null) {
                    try {
                        f.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        if (bytesMap.size() != paths.size()) {
            process.end(-1, "paths may contains same class name!");
            return;
        }

        Set<Class<?>> set = new HashSet<Class<?>>();
        List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (bytesMap.containsKey(clazz.getName())) {

                if (hashCode == null && classLoaderClass != null) {
                    List<ClassLoader> matchedClassLoaders = ClassLoaderUtils.getClassLoaderByClassName(inst, classLoaderClass);
                    if (matchedClassLoaders.size() == 1) {
                        hashCode = Integer.toHexString(matchedClassLoaders.get(0).hashCode());
                    } else if (matchedClassLoaders.size() > 1) {
                        Collection<ClassLoaderVO> classLoaderVOList = ClassUtils.createClassLoaderVOList(matchedClassLoaders);
                        RedefineModel classredefineModel = new RedefineModel()
                                .setClassLoaderClass(classLoaderClass)
                                .setMatchedClassLoaders(classLoaderVOList);
                        process.appendResult(classredefineModel);
                        process.end(-1, "Found more than one classloader by class name, please specify classloader with '-c <classloader hash>'");
                        return;
                    } else {
                        process.end(-1, "Can not find classloader by class name: " + classLoaderClass + ".");
                        return;
                    }
                }

                ClassLoader classLoader = clazz.getClassLoader();
                if (classLoader != null && hashCode != null && !Integer.toHexString(classLoader.hashCode()).equals(hashCode)) {
                    continue;
                }
                definitions.add(new ClassDefinition(clazz, bytesMap.get(clazz.getName())));
                redefineModel.addRedefineClass(clazz.getName());
                logger.info("Try redefine class name: {}, ClassLoader: {}", clazz.getName(), clazz.getClassLoader());
            }
        }
        try {
            //dump and backup class
            process.write("backup start");
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
            ClassDumpTransformer transformer = new ClassDumpTransformer(set);
            transformer.setSuffix(".class." + format.format(new Date()));
            Enhancer.enhance(inst, transformer, set);
            Map<Class<?>, File> backupClassMap = transformer.getDumpResult();
            for (Map.Entry<Class<?>, File> entry : backupClassMap.entrySet()) {
                process.write("bytecode backup class: " + entry.getKey().getName() + "\tpath:" + entry.getValue().getPath());
                if (!classBytesPathMap.containsKey(entry.getKey().getName())) {
                    classBytesPathMap.put(entry.getKey(), entry.getValue().getPath());
                }
            }
            process.write("backup success");
        } catch (Throwable t) {
            process.write("backup error, size: " + t.getMessage());
            logger.error("dump: fail to dump classes: ", t);
        }
        try {
            if (definitions.isEmpty()) {
                process.end(-1, "These classes are not found in the JVM and may not be loaded: " + bytesMap.keySet());
                return;
            }
            inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
            process.appendResult(redefineModel);
            process.end();
        } catch (Throwable e) {
            String message = "redefine error! " + e.toString();
            logger.error(message, e);
            process.end(-1, message);
        }

    }

    private static String readClassName(final byte[] bytes) {
        return new ClassReader(bytes).getClassName().replace("/", ".");
    }

    @Override
    public void complete(Completion completion) {
        if (!CompletionUtils.completeFilePath(completion)) {
            super.complete(completion);
        }
    }
}
