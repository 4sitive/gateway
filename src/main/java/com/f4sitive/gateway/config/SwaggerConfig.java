package com.f4sitive.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import springfox.documentation.swagger.web.SecurityConfiguration;
import springfox.documentation.swagger.web.SecurityConfigurationBuilder;
import springfox.documentation.swagger.web.SwaggerResource;
import springfox.documentation.swagger.web.SwaggerResourcesProvider;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("swagger")
@Configuration(proxyBeanMethods = false)
public class SwaggerConfig {
    @Getter
    private final List<SwaggerResource> resources = new ArrayList<>();

    @Bean
    SecurityConfiguration securityConfiguration() {
        return SecurityConfigurationBuilder.builder().build();
    }

    @Bean
    @Primary
    SwaggerResourcesProvider swaggerResourcesProvider() {
        return () -> resources;
    }

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("Authorization", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                        )
                        .addSecuritySchemes("OAuth2", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .clientCredentials(new OAuthFlow()
                                                .tokenUrl("/v1/auth/oauth/token")
                                        )
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl("/v1/auth/oauth/authorize")
                                                .tokenUrl("/v1/auth/oauth/token")
                                        )
                                )
                        )
                        .addSecuritySchemes("Auth-Token", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Auth-Token")
                        )
                        .addSecuritySchemes("User-Id", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("User-Id")
                        )
                        .addSecuritySchemes("Device-Id", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Device-Id")
                        )
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                        )
                )
                .addSecurityItem(new SecurityRequirement()
                        .addList("Authorization")
                        .addList("OAuth2")
                        .addList("Auth-Token")
                        .addList("User-Id")
                        .addList("Device-Id")
                        .addList("Bearer")
                );
    }
}
