package com.f4sitive.gateway.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.PropertiesRouteDefinitionLocator;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractChangeRequestUriGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;

@Configuration(proxyBeanMethods = false)
public class RouteConfig {
    @Bean
    AbstractGatewayFilterFactory<Object> stickySessionGatewayFilterFactory(Optional<LoadBalancerProperties> properties) {
        return new AbstractGatewayFilterFactory<Object>() {
            @Override
            public GatewayFilter apply(Object config) {
                return new OrderedGatewayFilter((exchange, chain) -> {
                    properties.map(LoadBalancerProperties::getStickySession)
                            .filter(stickySession -> StringUtils.hasText(stickySession.getInstanceIdCookieName()))
                            .flatMap(stickySession -> Optional.ofNullable(exchange.<Response<ServiceInstance>>getAttribute(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR))
                                    .filter(Response::hasServer)
                                    .map(response -> {
                                        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(stickySession.getInstanceIdCookieName(), response.getServer().getInstanceId());
                                        Optional.ofNullable(exchange.<URI>getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR))
                                                .map(URI::getPath)
                                                .map(requestUriPath -> StringUtils.trimTrailingCharacter(requestUriPath, '/'))
                                                .filter(StringUtils::hasText)
                                                .flatMap(requestUriPath -> Optional.ofNullable(exchange.<Collection<URI>>getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR))
                                                        .flatMap(originalUris -> originalUris.stream().map(URI::getPath).map(originalUriPath -> StringUtils.trimTrailingCharacter(originalUriPath, '/')).filter(StringUtils::hasText).findFirst())
                                                        .filter(originalUriPath -> requestUriPath.length() <= originalUriPath.length())
                                                        .map(originalUriPath -> originalUriPath.replaceAll(requestUriPath + "$", ""))
                                                        .filter(StringUtils::hasText))
                                                .ifPresent(builder::path);
                                        return builder.build();
                                    }))
                            .ifPresent(responseCookie -> exchange.getResponse().addCookie(responseCookie));
                    return chain.filter(exchange);
                }, ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 1);
            }

            @Override
            public String name() {
                return "StickySession";
            }
        };
    }

    @Bean
    @Order(-1)
    PropertiesRouteDefinitionLocator propertiesRouteDefinitionLocator(GatewayProperties properties) {
        return new PropertiesRouteDefinitionLocator(properties);
    }

    @Bean
    RewriteFunction<JsonNode, JsonNode> swaggerRewriteFunction(OpenAPI openAPI) {
        JsonNode securitySchemes = new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .valueToTree(Optional.ofNullable(openAPI.getComponents().getSecuritySchemes()).orElse(Collections.emptyMap()));
        Optional.ofNullable(securitySchemes.get("OAuth2"))
                .filter(JsonNode::isObject)
                .flatMap(oauth2 -> Optional.ofNullable(oauth2.get("flows"))
                        .filter(JsonNode::isObject)
                        .map(flows -> flows.get("authorizationCode"))
                        .filter(JsonNode::isObject))
                .ifPresent(authorizationCode -> {
                    ((ObjectNode) securitySchemes.get("OAuth2")).put("flow", "accessCode");
                    ((ObjectNode) securitySchemes.get("OAuth2")).setAll((ObjectNode) authorizationCode);
                });
        return (exchange, jsonNode) -> Optional.ofNullable(jsonNode)
                .filter(JsonNode::isObject)
                .map(readTree -> {
                    UriComponentsBuilder builder = UriComponentsBuilder.fromUri(exchange.getRequest().getURI());
                    Optional.ofNullable(exchange.<URI>getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR))
                            .map(URI::getPath)
                            .map(requestUriPath -> StringUtils.trimTrailingCharacter(requestUriPath, '/'))
                            .filter(StringUtils::hasText)
                            .flatMap(requestUriPath -> Optional.ofNullable(exchange.<Collection<URI>>getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR))
                                    .flatMap(originalUris -> originalUris.stream().map(URI::getPath).map(originalUriPath -> StringUtils.trimTrailingCharacter(originalUriPath, '/')).filter(StringUtils::hasText).findFirst())
                                    .filter(originalUriPath -> requestUriPath.length() <= originalUriPath.length())
                                    .map(originalUriPath -> originalUriPath.replaceAll(requestUriPath + "$", ""))
                                    .filter(StringUtils::hasText))
                            .ifPresent(builder::replacePath);
                    URI uri = builder.query(null).build(Collections.emptyMap());
                    Optional.ofNullable(readTree.get("servers"))
                            .filter(JsonNode::isArray)
                            .map(JsonNode::elements)
                            .ifPresent(servers -> servers.forEachRemaining(element -> Optional.ofNullable(element).filter(JsonNode::isObject).ifPresent(server -> ((ObjectNode) server).put("url", uri.toString()))));
                    ((ObjectNode) readTree).put("host", uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
                    ((ObjectNode) readTree).put("basePath", uri.getPath());
                    ((ObjectNode) readTree).set("securityDefinitions", securitySchemes);
                    ((ObjectNode) readTree).putArray("security").add(securitySchemes);
                    Optional.ofNullable(readTree.get("components"))
                            .filter(JsonNode::isObject)
                            .ifPresent(components -> ((ObjectNode) components).set("securitySchemes", securitySchemes));
                    Optional.ofNullable(readTree.get("paths"))
                            .filter(JsonNode::isObject)
                            .ifPresent(paths -> {
                                for (Iterator<JsonNode> path = paths.iterator(); path.hasNext(); ) {
                                    for (JsonNode method : path.next()) {
                                        Optional.ofNullable(method.get("parameters"))
                                                .filter(JsonNode::isArray)
                                                .ifPresent(parameters -> {
                                                    for (Iterator<JsonNode> parameter = parameters.iterator(); parameter.hasNext(); ) {
                                                        JsonNode next = parameter.next();
                                                        if (next.isObject() && Optional.ofNullable(next.get("in")).map(in -> "header".equals(in.asText())).orElse(false)) {
                                                            Optional.ofNullable(next.get("name"))
                                                                    .filter(name -> "User-Id".equals(name.asText()) || "Device-Id".equals(name.asText()) || "Auth-Token".equals(name.asText()))
                                                                    .ifPresent(name -> parameter.remove());
                                                        }
                                                    }
                                                });
                                    }
                                }
                            });
                    return readTree;
                })
                .map(Mono::just)
                .orElse(Mono.empty());
    }

    @Bean
    AbstractChangeRequestUriGatewayFilterFactory<Object> hostChangeRequestUriGatewayFilterFactory() {
        return new AbstractChangeRequestUriGatewayFilterFactory<Object>(Object.class) {
            @Override
            protected Optional<URI> determineRequestUri(ServerWebExchange exchange, Object config) {
                return Optional.ofNullable(exchange.<Route>getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR))
                        .flatMap(route -> Optional.ofNullable(exchange.<Map<String, String>>getAttribute(ServerWebExchangeUtils.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                                .map(attribute -> attribute.get("host"))
                                .map(host -> UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                                        .scheme(route.getUri().getScheme())
                                        .host(host)
                                        .build(ServerWebExchangeUtils.containsEncodedParts(exchange.getRequest().getURI()))
                                        .toUri()));
            }

            @Override
            public String name() {
                return "HostChangeRequestUri";
            }
        };
    }
}
