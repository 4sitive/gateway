package com.f4sitive.gateway.config;

import ch.qos.logback.access.AccessConstants;
import ch.qos.logback.classic.ClassicConstants;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.StreamUtils;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j(topic = "FILTER")
@Configuration(proxyBeanMethods = false)
public class FilterConfig {
    @Bean
    WebFilter contentWebFilter() {
        return (exchange, chain) -> {
            if (isManagement(exchange)) {
                return chain.filter(exchange);
            } else {
                return exchange.getPrincipal()
                        .map(principal -> Mono.justOrEmpty(Optional.ofNullable(principal.getName()).map(name -> exchange.getAttributes().put("org.apache.catalina.AccessLog.RemoteUser", name))))
                        .defaultIfEmpty(Mono.empty())
                        .flatMap(name -> chain.filter(exchange
                                .mutate()
                                .request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                                    @Override
                                    public Flux<DataBuffer> getBody() {
                                        return super.getBody().doOnNext(dataBuffer -> {
                                            try (InputStream inputStream = dataBuffer.factory().wrap(dataBuffer.asByteBuffer().asReadOnlyBuffer()).asInputStream()) {
                                                exchange.getAttributes().put(AccessConstants.LB_INPUT_BUFFER, StreamUtils.copyToByteArray(inputStream));
                                            } catch (Exception e) {
                                                log.error(e.getMessage(), e);
                                            }
                                        });
                                    }
                                })
                                .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                                    @Override
                                    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                                        return writeWith(Flux.from(body).flatMapSequential(p -> p));
                                    }

                                    @Override
                                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                        return super.writeWith(DataBufferUtils.join(body).doOnNext(dataBuffer -> {
                                            try (InputStream inputStream = dataBuffer.factory().wrap(dataBuffer.asByteBuffer().asReadOnlyBuffer()).asInputStream()) {
                                                exchange.getAttributes().put(AccessConstants.LB_OUTPUT_BUFFER, StreamUtils.copyToByteArray(inputStream));
                                            } catch (Exception e) {
                                                log.error(e.getMessage(), e);
                                            }
                                        }));
                                    }
                                })
                                .build()));
            }
        };
    }

    @Bean
    WebFilter corsWebFilter(List<org.springframework.web.cors.reactive.CorsConfigurationSource> sources) {
        return new CorsWebFilter(exchange -> sources.stream().map(source -> source.getCorsConfiguration(exchange)).filter(Objects::nonNull).findFirst().orElse(null));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    WebFilter tracerWebFilter(Optional<Tracer> tracer) {
        return (exchange, chain) -> {
            if (!isManagement(exchange)) {
                exchange.getAttributes().put(AccessConstants.LB_INPUT_BUFFER, new byte[0]);
            }
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

    private boolean isManagement(ServerWebExchange exchange) {
        return WebServerApplicationContext.hasServerNamespace(exchange.getApplicationContext(), "management");
    }
}
