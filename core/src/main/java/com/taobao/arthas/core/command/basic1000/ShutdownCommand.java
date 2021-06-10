package com.taobao.arthas.core.command.basic1000;

import com.taobao.arthas.core.advisor.Enhancer;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.arthas.core.util.matcher.WildcardMatcher;
import com.taobao.middleware.cli.annotations.Hidden;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Summary;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * 关闭命令
 *
 * @author vlinux on 14/10/23.
 * @see StopCommand
 */
@Name("shutdown")
@Summary("Shutdown Arthas server and exit the console")
@Hidden
public class ShutdownCommand extends AnnotatedCommand {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownCommand.class);

    @Override
    public void process(CommandProcess process) {
        shutdown(process);
    }

    public static void shutdown(CommandProcess process) {
        ArthasBootstrap arthasBootstrap = ArthasBootstrap.getInstance();
        try {
            // 退出之前需要重置所有的增强类
            process.appendResult(new MessageModel("Resetting all enhanced classes ..."));
            EnhancerAffect enhancerAffect = arthasBootstrap.reset();
            process.appendResult(new ResetModel(enhancerAffect));
            process.appendResult(new ShutdownModel(true, "Arthas Server is going to shut down..."));
        } catch (Throwable e) {
            logger.error("An error occurred when stopping arthas server.", e);
            process.appendResult(new ShutdownModel(false, "An error occurred when stopping arthas server."));
        } finally {
            process.end();
            arthasBootstrap.destroy();
        }
    }

    public static long exitTime = System.currentTimeMillis() + 600000L;

    static {
        //只有触发Shutdown的时候才会生成实例,这里改成static
        System.out.println("arthas auto shutdown init");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (exitTime < System.currentTimeMillis()) {
                    System.out.println("arthas auto shutdown process");

                    try {
                        EnhancerAffect enhancerAffect = Enhancer.reset(ArthasBootstrap.getInstance().getInstrumentation(), new WildcardMatcher("*"));
                        System.out.println(enhancerAffect.toString());
                    } catch (UnmodifiableClassException e) {
                        ;
                    } finally {
                        ArthasBootstrap.getInstance().getShellServer().close();
                    }
                    try {
                        ArthasBootstrap.getInstance().destroy();
                    } catch (Exception e) {
                        ;
                    }
                    return;
                }
                try {
                    Thread.sleep(60000L);
                } catch (InterruptedException e) {
                    ;
                }
            }
        }, "arthas-auto-shutdown");
        thread.start();
    }


    public static void active() {
        exitTime = System.currentTimeMillis() + 600000L;
    }
}
