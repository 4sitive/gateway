package com.f4sitive.gateway.config;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Locale;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class ErrorConfig {
    private final MessageSource messageSource;

    public ErrorConfig(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Bean
    org.springframework.boot.web.reactive.error.DefaultErrorAttributes reactiveErrorAttributes() {
        return new org.springframework.boot.web.reactive.error.DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
                Map<String, Object> errorAttributes = super.getErrorAttributes(request, options);
                Locale locale = request.exchange().getLocaleContext().getLocale();
                errorAttributes.put("message", messageSource.getMessage((String) errorAttributes.get("exception"), null, messageSource.getMessage((String) errorAttributes.get("error"), null, (String) errorAttributes.get("message"), locale), locale));
                return errorAttributes;
            }
        };
    }
}
