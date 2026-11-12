package com.physicscube.luma.shared.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 429 with Retry-After once a client exceeds a route's limit. Runs before
 * authentication and counts every attempt, successful or not. If the counter store
 * cannot be reached the request goes through (fail open) and a warning is logged:
 * the per-account lockouts still apply, and sign-in stays available.
 */
class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private final RateLimitCounters counters;
    private final Map<String, Bucket> routes;

    record Bucket(String name, RateLimitProperties.Rule rule) {
    }

    AuthRateLimitFilter(RateLimitCounters counters, RateLimitProperties properties) {
        this.counters = counters;
        Bucket signIn = new Bucket("sign-in", properties.signIn());
        Bucket mfa = new Bucket("mfa", properties.mfa());
        Bucket links = new Bucket("link-redemption", properties.linkRedemption());
        Bucket reset = new Bucket("reset-request", properties.resetRequest());
        this.routes = Map.of(
                "/api/v1/auth/login", signIn,
                "/api/v1/auth/mfa/enroll", mfa,
                "/api/v1/auth/mfa/verify", mfa,
                "/api/v1/auth/activate", links,
                "/api/v1/auth/password-reset/confirm", links,
                "/api/v1/auth/password-reset/request", reset);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !routes.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = routes.get(request.getRequestURI());
        Duration window = bucket.rule().window();
        Instant now = Instant.now();
        Instant start = RateLimitCounters.windowStart(now, window);
        int hits;
        try {
            hits = counters.hit(bucket.name(), ClientAddress.key(request.getRemoteAddr()), start);
        } catch (DataAccessException e) {
            log.warn("Rate limit counter unavailable for {}; allowing the request", bucket.name());
            chain.doFilter(request, response);
            return;
        }
        if (hits <= bucket.rule().limit()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, Duration.between(now, start.plus(window)).toSeconds());
        if (hits == bucket.rule().limit() + 1) {
            // Once per client and window; the address itself is not logged.
            log.info("Rate limit reached for {}", bucket.name());
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        long minutes = (retryAfter + 59) / 60;
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Too many attempts from this network. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".\"}");
    }
}
