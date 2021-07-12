package com.f4sitive.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.server.WebFilter;

@Configuration(proxyBeanMethods = false)
public class FilterConfig {
    @Bean
    WebFilter serverWebExchangeContextFilter() {
        return new ServerWebExchangeContextFilter();
    }
}
