package com.taobao.arthas.core.command.basic1000;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

import com.taobao.arthas.core.advisor.Enhancer;
import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.arthas.core.util.matcher.WildcardMatcher;
import com.taobao.middleware.cli.annotations.Hidden;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Summary;

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

    @Override
    public void process(CommandProcess process) {
        shutdown(process);
    }

    public static void shutdown(CommandProcess process) {
        try {
            // 退出之前需要重置所有的增强类
            Instrumentation inst = process.session().getInstrumentation();
            EnhancerAffect enhancerAffect = Enhancer.reset(inst, new WildcardMatcher("*"));
            process.write(enhancerAffect.toString()).write("\n");
            process.write("Arthas Server is going to shut down...\n");
        } catch (UnmodifiableClassException e) {
            // ignore
        } finally {
            process.end();
            ShellServer server = process.session().getServer();
            server.close();
        }
    }

    public static void active() {
        exitTime = System.currentTimeMillis() + 600000L;
    }
}
