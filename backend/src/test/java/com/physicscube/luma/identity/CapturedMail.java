package com.physicscube.luma.identity;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Captures account emails instead of sending them. Test classes that import this and
 * declare the same {@code luma.mail.*} test properties (copy them exactly, in order) share
 * one application context.
 */
@TestConfiguration
public class CapturedMail {
    public record Mail(String kind, String to, String link, Instant expiresAt) {
    }

    public static final BlockingQueue<Mail> SENT = new LinkedBlockingQueue<>();

    /** The next this-many invitation sends throw, as an unreachable mail server would. */
    public static final java.util.concurrent.atomic.AtomicInteger FAIL_NEXT = new java.util.concurrent.atomic.AtomicInteger();

    @Bean
    @Primary
    AccountMailer capturingMailer() {
        return new AccountMailer() {
            @Override
            public void invitation(String to, String link, Instant expiresAt) {
                if (FAIL_NEXT.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
                    throw new IllegalStateException("simulated mail server failure");
                }
                SENT.add(new Mail("invitation", to, link, expiresAt));
            }

            @Override
            public void passwordReset(String to, String link, Instant expiresAt) {
                SENT.add(new Mail("reset", to, link, expiresAt));
            }
        };
    }
}
