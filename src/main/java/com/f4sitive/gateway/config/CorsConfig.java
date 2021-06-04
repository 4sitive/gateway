package com.f4sitive.gateway.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ConfigurationProperties("cors")
@Configuration(proxyBeanMethods = false)
public class CorsConfig {
    @Getter
    private final Map<String, Cors> mapping = new LinkedHashMap<>();

    @Bean
    org.springframework.web.cors.reactive.CorsConfigurationSource reactiveCorsConfigurationSource() {
        org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource() {
            @Override
            public CorsConfiguration getCorsConfiguration(ServerWebExchange exchange) {
                return WebServerApplicationContext.hasServerNamespace(exchange.getApplicationContext(), "management") ? null : super.getCorsConfiguration(exchange);
            }
        };
        source.setCorsConfigurations(mapping.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        return source;
    }

    public static class Cors extends CorsConfiguration {
        Cors() {
            applyPermitDefaultValues();
        }
    }
}
