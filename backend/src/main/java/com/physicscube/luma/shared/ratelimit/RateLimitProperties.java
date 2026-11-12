package com.physicscube.luma.shared.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-client limits on the public auth routes. Defaults are generous on purpose: many
 * students share one public address behind mobile carrier NAT. Accounts have their own
 * lockout (sign-in, MFA) and hourly reset limit on top of these.
 */
@ConfigurationProperties("luma.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        /** POST /auth/login. */
        @DefaultValue({"60", "PT5M"}) Rule signIn,
        /** POST /auth/mfa/enroll and /auth/mfa/verify. */
        @DefaultValue({"60", "PT5M"}) Rule mfa,
        /** POST /auth/activate and /auth/password-reset/confirm (redeeming a 256-bit link). */
        @DefaultValue({"30", "PT10M"}) Rule linkRedemption,
        /** POST /auth/password-reset/request: limits email sent to many addresses from one place. */
        @DefaultValue({"10", "PT1H"}) Rule resetRequest) {

    public record Rule(int limit, Duration window) {
        public Rule {
            if (limit < 1 || window == null || window.compareTo(Duration.ofSeconds(10)) < 0 || window.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("luma.rate-limit rules need a limit of at least 1 and a window of 10 seconds to 1 day");
            }
        }
    }
}
