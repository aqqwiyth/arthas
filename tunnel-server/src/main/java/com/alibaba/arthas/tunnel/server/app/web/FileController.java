package com.alibaba.arthas.tunnel.server.app.web;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * 补充一个文件上传的接口提供class导入.再同步到目标容器.
 * class默认最大为10K
 */
@Controller
public class FileController {

    @RequestMapping(value = "/file/upload")
    @ResponseBody
    public Map<String, String> execute(@RequestParam(value = "ip", required = true) String ip, HttpServletRequest httpServletRequest) {
        MultipartHttpServletRequest request = (MultipartHttpServletRequest) httpServletRequest;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
        Map<String, String> map = new HashMap<>();
        request.getFileMap().entrySet().forEach(entry -> {
            try {
                long max = Long.valueOf(System.getProperty("max.file.size", "10"));
                if (entry.getValue().getBytes().length / 1024 > max) {
                    map.put(entry.getValue().getOriginalFilename(), entry.getValue().getBytes().length / 1024 + "K文件过大!");
                    return;
                }
                String fileName = format.format(new Date()) + "-" + UUID.randomUUID().toString();
                IOUtils.copy(entry.getValue().getInputStream(), new FileOutputStream(new File("/tmp/" + fileName)));
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("file " + "/tmp/" + fileName).getInputStream()));
                String fileType = bufferedReader.readLine();
                bufferedReader.close();
                // class 0xCAFEBABE
                // magic file
                // compiled Java class data, version 52.0
                if (fileType.toLowerCase().contains("java class data")) {
                    map.put(entry.getValue().getOriginalFilename(), "文件类型不合法:" + fileType);
                    return;
                }
                IOUtils.copy(entry.getValue().getInputStream(), new FileOutputStream(new File("/tmp/" + fileName + entry.getValue().getOriginalFilename())));
                map.put(entry.getValue().getOriginalFilename(), "redefine /tmp/" + fileName);
                System.out.println("scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no /tmp/" + fileName + "  tomcat@" + ip + ":/tmp/" + fileName);
                Process process = Runtime.getRuntime().exec("scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no /tmp/" + fileName + "  tomcat@" + ip + ":/tmp/" + fileName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        return map;
    }
}
