package com.physicscube.luma.identity;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends reset emails after the request has committed and answered, so response timing
 * does not depend on the mail server or reveal whether an account exists. Bounded:
 * when the queue is full the message is dropped and the person can ask again. Not an
 * Executor bean, so Spring Boot's own task executor is unaffected.
 */
@Component
class PasswordResetDelivery {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetDelivery.class);

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "password-reset-mail");
                thread.setDaemon(true);
                return thread;
            }, (runnable, pool) -> log.warn("Password reset email dropped: delivery queue full"));

    void submit(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
