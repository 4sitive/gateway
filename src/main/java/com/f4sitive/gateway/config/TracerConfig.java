package com.f4sitive.gateway.config;

import brave.baggage.*;
import ch.qos.logback.classic.ClassicConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TracerConfig {
    BaggageField REQUEST_REQUEST_URI = BaggageField.create(ClassicConstants.REQUEST_REQUEST_URI);
    BaggageField REQUEST_QUERY_STRING = BaggageField.create(ClassicConstants.REQUEST_QUERY_STRING);
    BaggageField REQUEST_METHOD = BaggageField.create(ClassicConstants.REQUEST_METHOD);

    @Bean
    BaggagePropagationCustomizer baggagePropagationCustomizer() {
        return builder -> builder
                .add(BaggagePropagationConfig.SingleBaggageField.local(REQUEST_REQUEST_URI))
                .add(BaggagePropagationConfig.SingleBaggageField.local(REQUEST_QUERY_STRING))
                .add(BaggagePropagationConfig.SingleBaggageField.local(REQUEST_METHOD));
    }

    @Bean
    CorrelationScopeCustomizer correlationScopeCustomizer() {
        return builder -> builder
                .add(CorrelationScopeConfig.SingleCorrelationField.create(BaggageFields.PARENT_ID))
                .add(CorrelationScopeConfig.SingleCorrelationField.create(BaggageFields.SAMPLED))
                .add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(REQUEST_REQUEST_URI).flushOnUpdate().build())
                .add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(REQUEST_QUERY_STRING).flushOnUpdate().build())
                .add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(REQUEST_METHOD).flushOnUpdate().build());
    }
}
