package com.taobao.arthas.core.command.klass100;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.text.SimpleDateFormat;
import java.util.*;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.advisor.Enhancer;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.middleware.cli.annotations.*;
import org.objectweb.asm.ClassReader;

/**
 * Redefine Classes.
 *
 * @author hengyunabc 2018-07-13
 * @see java.lang.instrument.Instrumentation#redefineClasses(ClassDefinition...)
 */
@Name("redefine")
@Summary("Redefine classes. @see Instrumentation#redefineClasses(ClassDefinition...)|注意: Redefine后的类 reset/shutdown无法还原")
@Description(Constants.EXAMPLE +
        "  redefine /tmp/Test.class\n" +
        "  redefine -c 327a647b /tmp/Test.class /tmp/Test\\$Inner.class \n" +
        Constants.WIKI + Constants.WIKI_HOME + "redefine")
public class RedefineCommand extends AnnotatedCommand {
    private static final Logger logger = LoggerFactory.getLogger(RedefineCommand.class);
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    private String hashCode;

    private List<String> paths;

    // 类-字节码缓存
    protected final static Map<Class<?>/*Class*/, String /*bytes of Class*/> classBytesPathMap = new WeakHashMap<Class<?>, String>();

    @Option(shortName = "c", longName = "classloader")
    @Description("classLoader hashcode")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Argument(argName = "classfilePaths", index = 0)
    @Description(".class file paths")
    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    @Override
    public void process(CommandProcess process) {
        Instrumentation inst = process.session().getInstrumentation();

        for (String path : paths) {
            File file = new File(path);
            if (!file.exists()) {
                process.write("file does not exist, path:" + path + "\n");
                process.end();
                return;
            }
            if (!file.isFile()) {
                process.write("not a normal file, path:" + path + "\n");
                process.end();
                return;
            }
            if (file.length() >= MAX_FILE_SIZE) {
                process.write("file size: " + file.length() + " >= " + MAX_FILE_SIZE + ", path: " + path + "\n");
                process.end();
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
                process.write("" + e + "\n");
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
            process.write("paths may contains same class name!\n");
            process.end();
            return;
        }

        Set<Class<?>> set = new HashSet<Class<?>>();
        List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (bytesMap.containsKey(clazz.getName())) {
                if (hashCode != null && !Integer.toHexString(clazz.getClassLoader().hashCode()).equals(hashCode)) {
                    continue;
                }
                definitions.add(new ClassDefinition(clazz, bytesMap.get(clazz.getName())));
                set.add(clazz);
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
                process.write("These classes are not found in the JVM and may not be loaded: " + bytesMap.keySet()
                        + "\n");
                process.end();
                return;
            }
            inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
            process.write("redefine success, size: " + definitions.size() + "\n");
        } catch (Exception e) {
            process.write("redefine error! " + e + "\n");
        }

        process.end();
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
