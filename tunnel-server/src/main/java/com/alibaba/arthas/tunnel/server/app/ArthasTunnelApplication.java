package com.alibaba.arthas.tunnel.server.app;

import com.taobao.arthas.common.OSUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.alibaba.arthas.tunnel.server.app",
        "com.alibaba.arthas.tunnel.server.endpoint"})
public class ArthasTunnelApplication {

    public static void main(String[] args) {
        try {
            //初始化key,这个key从镜像里获得
            if (!OSUtils.isMac()) {
                Runtime.getRuntime().exec("chmod 0600 /root/.ssh/id_rsa");
            }
        } catch (Throwable e) {
            throw new RuntimeException("初始化失败,请配置chmod shell权限!");
        }
        //    sh as.sh  --tunnel-server 'ws://127.0.0.1:7777/ws' --agent-id 127.0.0.1 --attach-only 94012
        SpringApplication.run(ArthasTunnelApplication.class, args);
    }

}
