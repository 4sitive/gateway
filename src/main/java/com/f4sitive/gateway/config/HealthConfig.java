package com.f4sitive.gateway.config;

import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.health.*;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Configuration(proxyBeanMethods = false)
public class HealthConfig {
    private Health health = Health.up().build();
    private final Optional<Registration> registration;
    private final Optional<ServiceRegistry> serviceRegistry;

    HealthConfig(Optional<Registration> registration, Optional<ServiceRegistry> serviceRegistry) {
        this.registration = registration;
        this.serviceRegistry = serviceRegistry;
    }

    Health health(String health) {
        this.health = Stream.of("true", "on", "yes", "1", "t", "y", "o", "enable").anyMatch(health::equalsIgnoreCase) ? Health.up().build() : Health.down().build();
        this.serviceRegistry
                .<Consumer<Registration>>map(serviceRegistry -> Status.UP.equals(this.health.getStatus()) ? serviceRegistry::register : serviceRegistry::deregister)
                .ifPresent(this.registration::ifPresent);
        return this.health;
    }

    @Bean
    ReactiveHealthIndicator reactiveHealthIndicator() {
        return () -> Mono.just(health);
    }

    @Bean
    ReactiveHealthEndpointWebExtension reactiveHealthEndpointWebExtension(ReactiveHealthContributorRegistry reactiveHealthContributorRegistry, HealthEndpointGroups groups) {
        return new ReactiveHealthEndpointWebExtension(reactiveHealthContributorRegistry, groups) {
            @WriteOperation
            public Mono<Health> health(@Selector String health) {
                return Mono.just(HealthConfig.this.health(health));
            }
        };
    }
}
