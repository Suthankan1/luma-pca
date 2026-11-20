package com.physicscube.luma.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Measures API work up to response commit, including authentication and controller execution. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
class ApiTimingFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        var timed = new OnCommittedResponseWrapper(response) {
            private boolean recorded;

            @Override
            protected void onResponseCommitted() {
                if (!recorded && !response.isCommitted()) {
                    response.addHeader("Server-Timing", String.format(Locale.ROOT, "api;dur=%.1f",
                            (System.nanoTime() - started) / 1_000_000.0));
                    recorded = true;
                }
            }
        };
        chain.doFilter(request, timed);
        if (!request.isAsyncStarted()) timed.onResponseCommitted();
    }
}
