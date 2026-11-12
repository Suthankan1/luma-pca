package com.physicscube.luma.zoom.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ZoomApiProperties.class)
class ZoomApiConfig {
    /** Only when the Zoom app's credentials are configured; nothing calls Zoom otherwise. */
    @Bean
    @ConditionalOnExpression("'${luma.zoom.api.client-secret:}' != ''")
    ZoomApiClient zoomApiClient(ZoomApiProperties properties) {
        return new ZoomApiClient(properties);
    }
}
