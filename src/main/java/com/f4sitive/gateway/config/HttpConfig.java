package com.f4sitive.gateway.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JettyClientHttpConnector;
import org.springframework.http.client.reactive.JettyResourceFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

@Slf4j(topic = "HTTP")
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties("http")
public class HttpConfig {
    @Getter
    private final Proxy proxy = new Proxy();

    @Setter
    private long threshold;

    @Bean
    WebClientCustomizer noContentCustomizer() {
        return builder -> builder.filter(ExchangeFilterFunction.ofResponseProcessor(clientResponse -> Mono.just(HttpStatus.NO_CONTENT.equals(clientResponse.statusCode()) ? clientResponse.mutate().body("").build() : clientResponse)));
    }

    @Bean
    JettyResourceFactory jettyResourceFactory() {
        return new JettyResourceFactory();
    }

    @Bean
    @Lazy
    ClientHttpConnector clientHttpConnector(Optional<JettyResourceFactory> jettyResourceFactory) {
        HttpClient httpClient = new HttpClient(new SslContextFactory.Client(true)) {
            @Override
            public Request newRequest(URI uri) {
                Request newRequest = super.newRequest(uri);
                newRequest.attribute("elapsed_time", System.nanoTime());
                newRequest.onRequestContent((request, content) -> {
                    try (InputStream inputStream = DefaultDataBufferFactory.sharedInstance.wrap(content.asReadOnlyBuffer()).asInputStream()) {
                        request.attribute("request_content", new String(StreamUtils.copyToByteArray(inputStream)));
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                });
                newRequest.onResponseContent((response, content) -> {
                    try (InputStream inputStream = DefaultDataBufferFactory.sharedInstance.wrap(content.asReadOnlyBuffer()).asInputStream()) {
                        response.getRequest().attribute("response_content", new String(StreamUtils.copyToByteArray(inputStream)));
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                });
                newRequest.onComplete(complete -> {
                    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - (long) complete.getRequest().getAttributes().get("elapsed_time"));
                    int statusCode = complete.getResponse().getStatus();
                    if (threshold <= elapsedTime || complete.getFailure() != null || Optional.ofNullable(HttpStatus.resolve(statusCode)).map(HttpStatus::isError).orElse(true)) {
                        StringBuilder requestContent = Optional.ofNullable((String) complete.getRequest().getAttributes().get("request_content"))
                                .orElseGet(String::new)
                                .codePoints()
                                .limit(2048)
                                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
                        HttpHeaders requestHeaders = complete.getRequest().getHeaders().stream().collect(HttpHeaders::new, (headers, httpField) -> headers.add(httpField.getName(), httpField.getValue()), HttpHeaders::putAll);
                        StringBuilder responseContent = Optional.ofNullable((String) complete.getRequest().getAttributes().get("response_content"))
                                .orElseGet(String::new)
                                .codePoints()
                                .limit(2048)
                                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
                        HttpHeaders responseHeaders = complete.getResponse().getHeaders().stream().collect(HttpHeaders::new, (headers, httpField) -> headers.add(httpField.getName(), httpField.getValue()), HttpHeaders::putAll);
                        log.info("elapsed_time: {}, method: {}, requested_uri: {}, status_code: {}, request_headers: {}, response_headers: {}, request_content: {}, response_content: {}",
                                elapsedTime,
                                complete.getRequest().getMethod(),
                                complete.getRequest().getURI(),
                                statusCode,
                                requestHeaders,
                                responseHeaders,
                                requestContent,
                                responseContent,
                                complete.getFailure()
                        );
                    }
                });
                return newRequest;
            }
        };
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy(proxy.getHost(), proxy.getPort()) {
            @Override
            public boolean matches(Origin origin) {
                return proxy.matches(origin.getAddress().getHost());
            }
        });
        httpClient.setConnectTimeout(Duration.ofSeconds(2L).toMillis());
        httpClient.setAddressResolutionTimeout(Duration.ofSeconds(2L).toMillis());
        httpClient.setIdleTimeout(Duration.ofMinutes(10L).toMillis());
        httpClient.setFollowRedirects(false);
        httpClient.setUserAgentField(null);
        return new JettyClientHttpConnector(httpClient, jettyResourceFactory.orElse(null));
    }

    @Getter
    @Setter
    public static class Proxy {
        private static final InetAddress LOOPBACK_ADDRESS = InetAddress.getLoopbackAddress();
        private String host = LOOPBACK_ADDRESS.getHostName();
        private int port = -1;
        private Set<String> included = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        private Set<String> excluded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        public boolean matches(String host) {
            if (host.equals(LOOPBACK_ADDRESS.getHostName()) || host.equals(this.host) || this.port == -1) {
                return false;
            }
            return this.included.stream().anyMatch(included -> included.equals(host)) && this.excluded.stream().noneMatch(excluded -> excluded.equals(host));
        }
    }
}
