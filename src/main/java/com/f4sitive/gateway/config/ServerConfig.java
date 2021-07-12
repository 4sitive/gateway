package com.f4sitive.gateway.config;

import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;


@Configuration(proxyBeanMethods = false)
public class ServerConfig {
    @Bean
    public NettyServerCustomizer nettyServerCustomizer() {
        return httpServer -> httpServer.idleTimeout(Duration.ofSeconds(5));
    }
}
