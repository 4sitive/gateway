package com.f4sitive.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ConfigurationProperties("zipkin")
@Configuration(proxyBeanMethods = false)
public class ZipkinConfig {
    @Setter
    private long threshold;

    @Getter
    private final List<String> ignorePaths = new ArrayList<>();

    @Bean
    @Order
    BeanPostProcessor reporterBeanPostProcessor() throws UnknownHostException {
        String hostname = InetAddress.getLocalHost().getHostName();
        return new BeanPostProcessor() {
            @Override
            @SuppressWarnings("unchecked")
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if ("zipkinReporter".equals(beanName) && bean instanceof Reporter) {
                    return (Reporter<Span>) span -> Optional.ofNullable(span)
                            .filter(s -> threshold <= TimeUnit.MICROSECONDS.toMillis(s.durationAsLong()) || test(s))
                            .map(s -> s.toBuilder().putTag("hostname", hostname).build())
                            .ifPresent(((Reporter<Span>) bean)::report);
                } else {
                    return bean;
                }
            }
        };
    }

    boolean test(Span span) {
        return span.tags().containsKey("error") && ignorePaths.stream().noneMatch(ignorePath -> Optional.ofNullable(span.tags().get("http.path")).map(path -> path.contains(ignorePath)).orElse(false));
    }
}