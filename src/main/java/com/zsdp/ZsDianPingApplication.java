package com.zsdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)//暴露代理对象
@MapperScan("com.zsdp.mapper")
@SpringBootApplication
public class ZsDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZsDianPingApplication.class, args);
    }

}
