package com.physicscube.luma.identity;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends account emails after the surrounding transaction commits, on a small bounded
 * queue, so a request never waits for the mail server (and a password-reset answer's
 * timing does not depend on whether an email was sent). When the queue is full the
 * message is dropped and logged; the person can ask again. Not an Executor bean, so
 * Spring Boot's own task executor is unaffected.
 */
@Component
class MailDelivery {
    private static final Logger log = LoggerFactory.getLogger(MailDelivery.class);

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "account-mail");
                thread.setDaemon(true);
                return thread;
            }, (runnable, pool) -> log.warn("Account email dropped: delivery queue full"));

    /** @param purpose a fixed word for logs, such as "invitation"; never personal data */
    void afterCommit(UUID accountId, String purpose, Runnable send) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> {
                    try {
                        send.run();
                    } catch (RuntimeException e) {
                        // Neither the address nor the link is logged.
                        log.warn("{} email for account {} failed: {}", purpose, accountId, e.getClass().getSimpleName());
                    }
                });
            }
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
