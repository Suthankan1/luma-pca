package com.physicscube.luma.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
// Only a running server has HTTP routes to protect; the operator command (owner bootstrap) runs without one.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                            "/api/v1/system/status", "/.well-known/jwks.json", "/api/v1/auth/student-id-login").permitAll()
                    // The refresh token in the body is the credential for refresh and logout.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/student-id-login", "/api/v1/auth/activate",
                            // The emailed reset token is the credential for confirming a reset.
                            "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm",
                            "/api/v1/auth/refresh", "/api/v1/auth/logout",
                            // Zoom signs each request; the signature over the raw body is the credential.
                            "/api/v1/zoom/webhooks").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout-all", "/api/v1/auth/password").authenticated()
                    // First gate only. Services repeat the role check and bind every query to
                    // the caller's academy; later slices add course and batch scope there.
                    // Assigned staff may read batches and rosters; services limit them to their assignments.
                    .requestMatchers(HttpMethod.GET, "/api/v1/batches", "/api/v1/batches/*", "/api/v1/batches/*/enrollments")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER")
                    // Analysts read the rule and academy-wide totals without student names.
                    .requestMatchers(HttpMethod.GET, "/api/v1/attendance-policy")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER", "ANALYST")
                    .requestMatchers(HttpMethod.GET, "/api/v1/attendance/summary").hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "ANALYST")
                    .requestMatchers(HttpMethod.GET, "/api/v1/class-sessions", "/api/v1/class-sessions/*",
                            "/api/v1/class-sessions/*/students", "/api/v1/class-sessions/*/attendance",
                            "/api/v1/class-sessions/*/attendance/export", "/api/v1/batches/*/attendance/export",
                            "/api/v1/batches/*/attendance/summary", "/api/v1/attendance/dashboard",
                            "/api/v1/class-sessions/*/zoom-run")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/me/assignments").hasAnyRole("INSTRUCTOR", "ATTENDANCE_OFFICER")
                    // Corrections: services limit attendance officers to students of their batches.
                    // Instructors open and close pulse questions in their classes; services check the class is theirs.
                    .requestMatchers(HttpMethod.POST, "/api/v1/class-sessions/*/pulses", "/api/v1/class-sessions/*/pulses/*/closure")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR")
                    .requestMatchers(HttpMethod.GET, "/api/v1/class-sessions/*/engagement")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/attendance-records/*/corrections")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "ATTENDANCE_OFFICER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/attendance-records/*/corrections")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER")
                    .requestMatchers("/api/v1/courses", "/api/v1/courses/**", "/api/v1/batches", "/api/v1/batches/**",
                            "/api/v1/students", "/api/v1/students/**", "/api/v1/enrollments/**", "/api/v1/assignments/**",
                            "/api/v1/class-sessions", "/api/v1/class-sessions/**",
                            "/api/v1/staff-accounts", "/api/v1/staff-accounts/**", "/api/v1/accounts/*/invitations",
                            "/api/v1/student-invitations", "/api/v1/student-invitations/progress", "/api/v1/student-invitations/failures", "/api/v1/audit-events",
                            "/api/v1/zoom/receipts", "/api/v1/zoom/receipts/**",
                            "/api/v1/zoom/occurrences", "/api/v1/zoom/occurrences/**", "/api/v1/zoom/occurrence-links",
                            "/api/v1/zoom/participants", "/api/v1/zoom/participants/unmatched", "/api/v1/zoom/identity-links", "/api/v1/zoom/identity-links/removals", "/api/v1/zoom/reports",
                            "/api/v1/zoom/webinar-lookup", "/api/v1/zoom/webinar-lookup/**",
                            "/api/v1/zoom/upcoming-webinars", "/api/v1/zoom/upcoming-webinars/*", "/api/v1/zoom/webinar-imports",
                            "/api/v1/attendance/pending", "/api/v1/attendance/finalisations",
                            "/api/v1/attendance-policy", "/api/v1/attendance-appeals", "/api/v1/attendance-appeals/*/resolution",
                            "/api/v1/engagement-settings",
                            "/api/v1/academy/classes", "/api/v1/academy/classes/link", "/api/v1/academy/classes/sync",
                            "/api/v1/zoom/monitors", "/api/v1/zoom/monitors/*/sync", "/api/v1/zoom/monitors/*/stop",
                            "/api/v1/zoom/monitors/*/resume", "/api/v1/zoom/monitors/*/academy-class")
                        .hasAnyRole("ACADEMY_OWNER", "ACADEMY_ADMIN")
                    // A student's own data; services resolve "own" from the token, never from input.
                    .requestMatchers(HttpMethod.GET, "/api/v1/me/student", "/api/v1/me/enrollments", "/api/v1/me/class-sessions",
                            "/api/v1/me/attendance", "/api/v1/me/attendance-overview").hasRole("STUDENT")
                    .requestMatchers(HttpMethod.POST, "/api/v1/me/attendance/*/appeals").hasRole("STUDENT")
                    // Engagement (006): a student's own consent, reactions and answers; services check consent and class.
                    .requestMatchers(HttpMethod.GET, "/api/v1/me/engagement-consent", "/api/v1/me/class-sessions/*/engagement").hasRole("STUDENT")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/me/engagement-consent").hasRole("STUDENT")
                    .requestMatchers(HttpMethod.POST, "/api/v1/me/class-sessions/*/reactions", "/api/v1/me/class-sessions/*/pulses/*/answers")
                        .hasRole("STUDENT")
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
