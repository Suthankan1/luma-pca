package com.physicscube.luma.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-account limit for heavier signed-in actions (attendance exports, Zoom lookups and report
 * fetches, engagement rankings): each one reads a lot of data or calls Zoom. Runs after Spring
 * Security, keyed by the account, and answers 429 with Retry-After like the sign-in limits.
 */
class AccountRateLimitInterceptor implements HandlerInterceptor {
    private static final Pattern HEAVY = Pattern.compile(
            "/api/v1/(class-sessions/[^/]+/attendance/export|batches/[^/]+/attendance/export|batches/[^/]+/engagement-ranking"
                    + "|zoom/webinar-lookup(/.*)?|zoom/reports)");

    private final RateLimitCounters counters;
    private final RateLimitProperties.Rule rule;

    AccountRateLimitInterceptor(RateLimitCounters counters, RateLimitProperties properties) {
        this.counters = counters;
        this.rule = properties.heavyStaff();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!HEAVY.matcher(request.getRequestURI()).matches()) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return true;
        }
        Instant now = Instant.now();
        Instant window = RateLimitCounters.windowStart(now, rule.window());
        if (counters.hit("heavy-staff", "account:" + authentication.getName(), window) <= rule.limit()) {
            return true;
        }
        long retryAfter = Math.max(1, window.plus(rule.window()).getEpochSecond() - now.getEpochSecond());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Too many of these requests from your account; try again in a few minutes\"}");
        return false;
    }
}
