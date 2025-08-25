package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {

    @Bean(destroyMethod = "shutdown") // 关键：指定销毁方法为 shutdown
    public ExecutorService voucherOrderExecutor() {
        return Executors.newFixedThreadPool(20);
    }
}