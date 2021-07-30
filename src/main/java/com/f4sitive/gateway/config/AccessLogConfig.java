package com.f4sitive.gateway.config;

import ch.qos.logback.access.AccessConstants;
import ch.qos.logback.access.joran.JoranConfigurator;
import ch.qos.logback.access.spi.AccessContext;
import ch.qos.logback.access.spi.AccessEvent;
import ch.qos.logback.access.spi.ServerAdapter;
import lombok.SneakyThrows;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.lang.NonNull;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.WebHandlerDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;


@Configuration(proxyBeanMethods = false)
class AccessLogConfig {
    @Bean
    WebFilter contentWebFilter() {
        return new WebFilter() {
            @SneakyThrows
            byte[] content(ByteBuffer byteBuffer) {
                return StreamUtils
                        .copyToByteArray(DefaultDataBufferFactory.sharedInstance.wrap(byteBuffer).asInputStream());
            }

            @Override
            @NonNull
            public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
                return exchange.getPrincipal()
                        .map(Principal::getName)
                        .<Void>flatMap(name -> {
                            exchange.getAttributes().put("org.apache.catalina.AccessLog.RemoteUser", name);
                            return Mono.empty();
                        })
                        .switchIfEmpty(chain.filter(exchange
                                .mutate()
                                .request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                                    @Override
                                    @NonNull
                                    public Flux<DataBuffer> getBody() {
                                        return super.getBody().doOnNext(dataBuffer -> {
                                            byte[] content = content(dataBuffer.asByteBuffer().asReadOnlyBuffer());
                                            exchange.getAttributes().put(AccessConstants.LB_INPUT_BUFFER, content);
                                        });
                                    }
                                })
                                .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                                    @Override
                                    @NonNull
                                    public Mono<Void> writeAndFlushWith(
                                            @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body) {
                                        return writeWith(Flux.from(body).flatMapSequential(publisher -> publisher));
                                    }

                                    @Override
                                    @NonNull
                                    public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
                                        return super.writeWith(DataBufferUtils.join(body).doOnNext(dataBuffer -> {
                                            byte[] content = content(dataBuffer.asByteBuffer().asReadOnlyBuffer());
                                            exchange.getAttributes().put(AccessConstants.LB_OUTPUT_BUFFER, content);
                                        }));
                                    }
                                })
                                .build()));
            }
        };
    }

    @SneakyThrows
    @Bean
    HttpHandlerDecoratorFactory httpHandlerDecoratorFactory(Environment environment) {
        AccessContext context = new AccessContext();
        List<String> profiles = new ArrayList<>(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.isEmpty()) {
            profiles.addAll(Arrays.asList(environment.getDefaultProfiles()));
        }
        profiles.forEach(profile -> context.putProperty(profile, profile));
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(ResourceUtils.getURL("classpath:logback-access-spring.xml"));
        context.start();
        return adapted -> {
            WebHandler delegate = new WebHandlerDecorator(((HttpWebHandlerAdapter) adapted).getDelegate()) {
                @Override
                @NonNull
                public Mono<Void> handle(@NonNull ServerWebExchange exchange) {
                    return super.handle(exchange).doFinally(signalType -> {
                        ServerAdapter adapter = reactiveServerAdapter(exchange);
                        AccessEvent event = reactiveAccessEvent(adapter, exchange);
                        event.setThreadName(Thread.currentThread().getName());
                        context.callAppenders(event);
                    });
                }
            };
            HttpWebHandlerAdapter httpHandler = new HttpWebHandlerAdapter(delegate) {
                @Override
                @NonNull
                protected ServerWebExchange createExchange(
                        @NonNull ServerHttpRequest request,
                        @NonNull ServerHttpResponse response
                ) {
                    ServerWebExchange exchange = super.createExchange(request, response);
                    exchange.getAttributes().put("elapsed_time", System.nanoTime());
                    return exchange;
                }
            };
            httpHandler.setSessionManager(((HttpWebHandlerAdapter) adapted).getSessionManager());
            httpHandler.setCodecConfigurer(((HttpWebHandlerAdapter) adapted).getCodecConfigurer());
            httpHandler.setLocaleContextResolver(((HttpWebHandlerAdapter) adapted).getLocaleContextResolver());
            Optional.ofNullable(((HttpWebHandlerAdapter) adapted).getForwardedHeaderTransformer())
                    .ifPresent(httpHandler::setForwardedHeaderTransformer);
            Optional.ofNullable(((HttpWebHandlerAdapter) adapted).getApplicationContext())
                    .ifPresent(httpHandler::setApplicationContext);
            httpHandler.afterPropertiesSet();
            return httpHandler;
        };
    }

    ServerAdapter reactiveServerAdapter(ServerWebExchange exchange) {
        return new ServerAdapter() {
            long elapsedTime(long elapsedTime) {
                return System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - elapsedTime);
            }

            @Override
            public long getRequestTimestamp() {
                return Optional.ofNullable(exchange.<Long>getAttribute("elapsed_time")).map(this::elapsedTime)
                        .orElse(-1L);
            }

            @Override
            public long getContentLength() {
                return -1;
            }

            @Override
            public int getStatusCode() {
                return -1;
            }

            @Override
            public Map<String, String> buildResponseHeaderMap() {
                return exchange.getResponse().getHeaders().entrySet().stream()
                        .collect(LinkedHashMap::new,
                                (map, entry) -> map.put(entry.getKey(), String.join(",", entry.getValue())),
                                Map::putAll);
            }
        };
    }

    AccessEvent reactiveAccessEvent(ServerAdapter adapter, ServerWebExchange exchange) {
        return new AccessEvent(null, null, adapter) {
            private Map<String, String> requestHeaderMap;
            private Map<String, String[]> requestParameterMap;

            @Override
            public String getRequestURI() {
                return exchange.getRequest().getURI().getPath();
            }

            @Override
            public String getQueryString() {
                return Optional.ofNullable(exchange.getRequest().getURI().getRawQuery()).filter(StringUtils::hasText)
                        .map("?"::concat).orElse("");
            }

            @Override
            public String getRequestURL() {
                return getMethod() + " " + getRequestURI() + getQueryString() + " " + getProtocol();
            }

            @Override
            public String getRemoteHost() {
                return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getHostString)
                        .orElse("");
            }

            @Override
            public String getRemoteUser() {
                return exchange.getAttribute("org.apache.catalina.AccessLog.RemoteUser");
            }

            @Override
            public String getProtocol() {
                return ServerHttpRequestDecorator.<HttpServerRequest>getNativeRequest(exchange.getRequest())
                        .version().text();
            }

            @Override
            public String getMethod() {
                return exchange.getRequest().getMethodValue();
            }

            @Override
            public String getRequestHeader(String key) {
                buildRequestHeaderMap();
                return Optional.ofNullable(requestHeaderMap.get(key.toLowerCase()))
                        .orElse("-");
            }

            @Override
            public Map<String, String> getRequestHeaderMap() {
                buildRequestHeaderMap();
                return requestHeaderMap;
            }

            @Override
            public void buildRequestHeaderMap() {
                if (requestHeaderMap == null) {
                    requestHeaderMap = exchange.getRequest().getHeaders().entrySet().stream()
                            .collect(() -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER),
                                    (map, entry) -> map.put(entry.getKey(), String.join(",", entry.getValue())),
                                    Map::putAll);
                }
            }

            @Override
            public Map<String, String[]> getRequestParameterMap() {
                buildRequestParameterMap();
                return requestParameterMap;
            }

            @Override
            public String[] getRequestParameter(String key) {
                buildRequestParameterMap();
                return Optional.ofNullable(requestParameterMap.get(key))
                        .orElseGet(() -> new String[]{"-"});
            }

            @Override
            public void buildRequestParameterMap() {
                if (requestParameterMap == null) {
                    requestParameterMap = exchange.getRequest().getQueryParams().entrySet().stream()
                            .collect(LinkedHashMap::new,
                                    (map, entry) -> map.put(entry.getKey(), entry.getValue().toArray(new String[0])),
                                    Map::putAll);
                }
            }

            @Override
            public long getContentLength() {
                return Optional.ofNullable(exchange.<byte[]>getAttribute(AccessConstants.LB_OUTPUT_BUFFER))
                        .map(responseContent -> (long) responseContent.length)
                        .orElseGet(getServerAdapter()::getContentLength);
            }

            @Override
            public int getStatusCode() {
                return ServerHttpResponseDecorator.<HttpServerResponse>getNativeResponse(exchange.getResponse())
                        .status().code();
            }

            @Override
            public String getRequestContent() {
                return new String(exchange.getAttributeOrDefault(AccessConstants.LB_INPUT_BUFFER, new byte[0]));
            }

            @Override
            public String getResponseContent() {
                return new String(exchange.getAttributeOrDefault(AccessConstants.LB_OUTPUT_BUFFER, new byte[0]));
            }

            @Override
            public void prepareForDeferredProcessing() {
                getRequestHeaderMap();
                getRequestParameterMap();
            }
        };
    }
}