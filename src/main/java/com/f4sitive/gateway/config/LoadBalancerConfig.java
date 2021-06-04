package com.f4sitive.gateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClientSpecification;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.DiscoveryClientServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.NumberUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.retry.Repeat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
public class LoadBalancerConfig {
    @Bean
    LoadBalancerClientFactory loadBalancerClientFactory(ObjectProvider<List<LoadBalancerClientSpecification>> configurations) {
        LoadBalancerClientFactory clientFactory = new LoadBalancerClientFactory();
        List<LoadBalancerClientSpecification> loadBalancerClientSpecifications = configurations.getIfAvailable(ArrayList::new);
        loadBalancerClientSpecifications.add(new LoadBalancerClientSpecification("default." + ServiceInstanceListSupplier.class.getName(), new Class[]{ServiceInstanceListSupplierConfig.class}));
        clientFactory.setConfigurations(loadBalancerClientSpecifications);
        return clientFactory;
    }

    private static ServiceInstanceListSupplier serviceInstanceListSupplier(ConfigurableApplicationContext context) {
        LoadBalancerProperties properties = context.getBean(LoadBalancerProperties.class);
        WebClient webClient = context.getBean(WebClient.Builder.class).build();
        ServiceInstanceListSupplier serviceInstanceListSupplier = ServiceInstanceListSupplier.builder()
                .withBase(new DelegatingServiceInstanceListSupplier(Optional.ofNullable(context.getBeanProvider(ReactiveDiscoveryClient.class).getIfAvailable())
                        .map(reactiveDiscoveryClient -> new DiscoveryClientServiceInstanceListSupplier(reactiveDiscoveryClient, context.getEnvironment()))
                        .orElseGet(() -> Optional.ofNullable(context.getBeanProvider(DiscoveryClient.class).getIfAvailable())
                                .map(discoveryClient -> new DiscoveryClientServiceInstanceListSupplier(discoveryClient, context.getEnvironment()))
                                .orElseThrow(() -> new IllegalArgumentException("DiscoveryClient may not be null")))) {
                    @Override
                    public Flux<List<ServiceInstance>> get() {
                        return delegate.get().repeatWhen(Repeat.onlyIf(repeatContext -> properties.getHealthCheck().getRefetchInstances()).fixedBackoff(properties.getHealthCheck().getRefetchInstancesInterval()));
                    }
                })
                .build(context);
        serviceInstanceListSupplier = ServiceInstanceListSupplier.builder()
                .withBase(new HealthCheckServiceInstanceListSupplier(serviceInstanceListSupplier, properties.getHealthCheck(), (serviceInstance, healthCheckPath) -> {
                    if (StringUtils.hasText(healthCheckPath) || StringUtils.hasText(serviceInstance.getMetadata().get("health-check.path"))) {
                        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(serviceInstance.getUri());
                        builder.path(Optional.ofNullable(serviceInstance.getMetadata().get("health-check.path")).filter(StringUtils::hasText).orElse(healthCheckPath));
                        Optional.ofNullable(serviceInstance.getMetadata().get("management.port")).map(managementPort -> NumberUtils.parseNumber(managementPort, Integer.class)).ifPresent(builder::port);
                        return webClient.get()
                                .uri(builder.build().toUri())
                                .headers(headers -> Optional.ofNullable(serviceInstance.getMetadata().get("user.name")).ifPresent(username -> headers.setBasicAuth(username, serviceInstance.getMetadata().getOrDefault("user.password", ""))))
                                .exchangeToMono(clientResponse -> clientResponse.releaseBody().thenReturn(HttpStatus.OK.value() == clientResponse.rawStatusCode()));
                    } else {
                        return Mono.just(true);
                    }
                }))
                .build(context);
        return ServiceInstanceListSupplier.builder()
                .withBase(serviceInstanceListSupplier)
                .withZonePreference()
                .withRequestBasedStickySession()
                .build(context);
    }

    protected static class ServiceInstanceListSupplierConfig extends DelegatingServiceInstanceListSupplier {
        ServiceInstanceListSupplierConfig(ConfigurableApplicationContext context) {
            super(serviceInstanceListSupplier(context));
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
            return delegate.get();
        }

        @Override
        public Flux<List<ServiceInstance>> get(Request request) {
            return delegate.get(request);
        }
    }
}
