package com.f4sitive.gateway.config;

import brave.baggage.*;
import ch.qos.logback.classic.ClassicConstants;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
public class TracerConfig {
    BaggageField requestRequestUri = BaggageField.create(ClassicConstants.REQUEST_REQUEST_URI);
    BaggageField requestQueryString = BaggageField.create(ClassicConstants.REQUEST_QUERY_STRING);
    BaggageField requestMethod = BaggageField.create(ClassicConstants.REQUEST_METHOD);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    WebFilter tracerWebFilter(Optional<Tracer> tracer) {
        return (exchange, chain) -> {
            exchange.getResponse().beforeCommit(() -> {
                Optional.ofNullable(exchange.<String>getAttribute(ServerWebExchange.LOG_ID_ATTRIBUTE)).ifPresent(logId -> exchange.getResponse().getHeaders().add("logId", logId));
                return Mono.empty();
            });
            return chain.filter(exchange)
                    .doOnSubscribe(subscription -> tracer.ifPresent(t -> {
                        Optional.ofNullable(t.currentSpan()).map(Span::context).ifPresent(context -> exchange.getResponse().beforeCommit(() -> {
                            Optional.ofNullable(context.traceId()).ifPresent(traceId -> exchange.getResponse().getHeaders().add("traceId", traceId));
                            Optional.ofNullable(context.spanId()).ifPresent(spanId -> exchange.getResponse().getHeaders().add("spanId", spanId));
                            Optional.ofNullable(context.parentId()).ifPresent(parentId -> exchange.getResponse().getHeaders().add("parentId", parentId));
                            return Mono.empty();
                        }));
                        t.createBaggage(ClassicConstants.REQUEST_REQUEST_URI, exchange.getRequest().getURI().getPath());
                        t.createBaggage(ClassicConstants.REQUEST_QUERY_STRING, exchange.getRequest().getURI().getQuery());
                        t.createBaggage(ClassicConstants.REQUEST_METHOD, exchange.getRequest().getMethodValue());
                    }));
        };
    }

    @Bean
    BaggagePropagationCustomizer baggagePropagationCustomizer() {
        return builder -> builder
                .add(BaggagePropagationConfig.SingleBaggageField.local(requestRequestUri))
                .add(BaggagePropagationConfig.SingleBaggageField.local(requestQueryString))
                .add(BaggagePropagationConfig.SingleBaggageField.local(requestMethod));
    }

    @Bean
    CorrelationScopeCustomizer correlationScopeCustomizer() {
        return builder -> builder
                .add(CorrelationScopeConfig.SingleCorrelationField.create(BaggageFields.PARENT_ID))
                .add(CorrelationScopeConfig.SingleCorrelationField.create(BaggageFields.SAMPLED))
                .add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(requestRequestUri).flushOnUpdate().build())
                .add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(requestQueryString).flushOnUpdate().build())
                .add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(requestMethod).flushOnUpdate().build());
    }
}
