package com.foursitive.gateway.config;

import ch.qos.logback.access.AccessConstants;
import ch.qos.logback.access.joran.JoranConfigurator;
import ch.qos.logback.access.pattern.AccessConverter;
import ch.qos.logback.access.spi.AccessContext;
import ch.qos.logback.access.spi.AccessEvent;
import ch.qos.logback.access.spi.ServerAdapter;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.spi.FilterReply;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.*;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.server.handler.WebHandlerDecorator;
import org.springframework.web.server.i18n.LocaleContextResolver;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;


@Slf4j
@Configuration(proxyBeanMethods = false)
public class WebServerFactoryConfig {
    @Bean
    public NettyServerCustomizer nettyServerCustomizer() {
        return httpServer -> httpServer.idleTimeout(Duration.ofSeconds(5));
    }

    @Bean
    public HttpHandler httpHandler(ApplicationContext applicationContext, ObjectProvider<WebFluxProperties> propsProvider) throws FileNotFoundException, JoranException {
        HttpHandler httpHandler = WebHttpHandlerBuilder.applicationContext(applicationContext).build();
        WebFluxProperties properties = propsProvider.getIfAvailable();
        if (properties != null && StringUtils.hasText(properties.getBasePath())) {
            Map<String, HttpHandler> handlersMap = Collections.singletonMap(properties.getBasePath(), httpHandler);
            httpHandler = new ContextPathCompositeHandler(handlersMap);
        }
        AccessContext context = new AccessContext();
        Arrays.stream(applicationContext.getEnvironment().getActiveProfiles()).forEach(profile -> context.putProperty(profile, profile));
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        jc.doConfigure(ResourceUtils.getURL("classpath:logback-access-spring.xml"));
        context.start();
        HttpWebHandlerAdapter delegate = (HttpWebHandlerAdapter) httpHandler;
        return new HttpWebHandlerAdapter(new WebHandlerDecorator((WebHandler) httpHandler) {
            @Override
            public Mono<Void> handle(ServerWebExchange exchange) {
                return super.handle(exchange).doFinally(signalType -> {
                    ServerAdapter adapter = new ServerAdapter() {
                        @Override
                        public long getRequestTimestamp() {
                            return Optional.ofNullable(exchange.<Long>getAttribute("startTime"))
                                    .map(startTime -> System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime))
                                    .orElse(-1L);
                        }

                        @Override
                        public long getContentLength() {
                            return -1;
                        }

                        @Override
                        public int getStatusCode() {
                            return 0;
                        }

                        @Override
                        public Map<String, String> buildResponseHeaderMap() {
                            HttpHeaders responseHeaders = ServerHttpResponseDecorator.<HttpServerResponse>getNativeResponse(exchange.getResponse()).responseHeaders();
                            return responseHeaders.entries().stream().collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), String.join(",", new LinkedHashSet<>(responseHeaders.getAll(entry.getKey())))), Map::putAll);
                        }
                    };
                    AccessEvent event = new AccessEvent(null, null, adapter) {
                        Map<String, String> requestHeaderMap;
                        Map<String, String[]> requestParameterMap;

                        @Override
                        public String getRequestURI() {
                            return exchange.getRequest().getURI().getPath();
                        }

                        @Override
                        public String getQueryString() {
                            return Optional.ofNullable(exchange.getRequest().getURI().getRawQuery())
                                    .filter(rawQuery -> !rawQuery.isEmpty())
                                    .map(rawQuery -> AccessConverter.QUESTION_CHAR + rawQuery)
                                    .orElse("");
                        }

                        @Override
                        public String getRequestURL() {
                            return getMethod() + AccessConverter.SPACE_CHAR + getRequestURI() + getQueryString() + AccessConverter.SPACE_CHAR + getProtocol();
                        }

                        @Override
                        public String getRemoteHost() {
                            return Optional.ofNullable(exchange.getRequest().getRemoteAddress()).map(InetSocketAddress::getHostString).orElse("");
                        }

                        @Override
                        public String getRemoteUser() {
                            return exchange.getAttribute("org.apache.catalina.AccessLog.RemoteUser");
                        }

                        @Override
                        public String getProtocol() {
                            return ServerHttpRequestDecorator.<HttpServerRequest>getNativeRequest(exchange.getRequest()).version().text();
                        }

                        @Override
                        public String getMethod() {
                            return exchange.getRequest().getMethodValue();
                        }

                        @Override
                        public String getRequestHeader(String key) {
                            buildRequestHeaderMap();
                            return requestHeaderMap.get(key.toLowerCase());
                        }

                        @Override
                        public Map<String, String> getRequestHeaderMap() {
                            buildRequestHeaderMap();
                            return requestHeaderMap;
                        }

                        @Override
                        public void buildRequestHeaderMap() {
                            if (requestHeaderMap == null) {
                                requestHeaderMap = exchange.getRequest().getHeaders().entrySet().stream().collect(() -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER), (map, entry) -> map.put(entry.getKey(), String.join(",", entry.getValue())), Map::putAll);
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
                            return requestParameterMap.get(key);
                        }

                        @Override
                        public void buildRequestParameterMap() {
                            if (requestParameterMap == null) {
                                requestParameterMap = exchange.getRequest().getQueryParams().entrySet().stream().collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue().toArray(new String[0])), Map::putAll);
                            }
                        }

                        @Override
                        public long getContentLength() {
                            return Optional.ofNullable(exchange.<byte[]>getAttribute(AccessConstants.LB_OUTPUT_BUFFER))
                                    .map(responseContent -> responseContent.length)
                                    .orElse(-1);
                        }

                        @Override
                        public int getStatusCode() {
                            return ServerHttpResponseDecorator.<HttpServerResponse>getNativeResponse(exchange.getResponse()).status().code();
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
                        }
                    };
                    event.setThreadName(Thread.currentThread().getName());
                    if (context.getFilterChainDecision(event) != FilterReply.DENY) {
                        context.callAppenders(event);
                    }
                });
            }
        }) {
            @Override
            public ServerCodecConfigurer getCodecConfigurer() {
                return delegate.getCodecConfigurer();
            }

            @Override
            public LocaleContextResolver getLocaleContextResolver() {
                return delegate.getLocaleContextResolver();
            }

            @Override
            protected ServerWebExchange createExchange(ServerHttpRequest request, ServerHttpResponse response) {
                ServerWebExchange exchange = super.createExchange(request, response);
                exchange.getAttributes().put("startTime", System.nanoTime());
                return exchange;
            }

            {
                setSessionManager(delegate.getSessionManager());
                setApplicationContext(delegate.getApplicationContext());
            }
        };
    }
}
