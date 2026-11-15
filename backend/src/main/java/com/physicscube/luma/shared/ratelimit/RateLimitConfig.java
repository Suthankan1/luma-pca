package com.physicscube.luma.shared.ratelimit;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(name = "luma.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitConfig {
    private final RateLimitCounters counters;

    RateLimitConfig(RateLimitCounters counters) {
        this.counters = counters;
    }

    /** Before Spring Security, so refused requests cost no authentication work. */
    @Bean
    FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilter(RateLimitProperties properties) {
        FilterRegistrationBean<AuthRateLimitFilter> registration =
                new FilterRegistrationBean<>(new AuthRateLimitFilter(counters, properties));
        registration.addUrlPatterns("/api/v1/auth/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }

    /** Heavier signed-in actions, per account (after authentication). */
    @Bean
    org.springframework.web.servlet.config.annotation.WebMvcConfigurer accountRateLimits(RateLimitProperties properties) {
        AccountRateLimitInterceptor interceptor = new AccountRateLimitInterceptor(counters, properties);
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override
            public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    /** Client addresses are kept at most a day. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void deleteOldCounters() {
        counters.deleteOlderThan(Instant.now().minus(Duration.ofDays(1)).minus(Duration.ofHours(1)));
    }
}
