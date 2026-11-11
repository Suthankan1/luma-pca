package com.physicscube.luma.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API. Every route is denied unless listed here; add a route
 * only together with its authorization tests.
 */
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                            "/api/v1/system/status", "/.well-known/jwks.json").permitAll()
                    // The refresh token in the body is the credential for refresh and logout.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/activate",
                            "/api/v1/auth/refresh", "/api/v1/auth/logout",
                            // The challenge token from the password step is the credential here.
                            "/api/v1/auth/mfa/enroll", "/api/v1/auth/mfa/verify").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout-all", "/api/v1/auth/mfa/reset").authenticated()
                    // First gate only. Services repeat the role check and bind every query to
                    // the caller's academy; later slices add course and batch scope there.
                    .requestMatchers("/api/v1/courses", "/api/v1/courses/**", "/api/v1/batches", "/api/v1/batches/**")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN")
                    .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The API never authenticates from cookies, so there is no ambient credential
                // for a cross-site request to ride on. Browser CSRF protection is the BFF's job.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
