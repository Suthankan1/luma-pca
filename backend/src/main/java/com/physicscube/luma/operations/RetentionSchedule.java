package com.physicscube.luma.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the retention job nightly (02:30 Asia/Colombo) unless {@code luma.retention.enabled=false}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "luma.retention.enabled", havingValue = "true", matchIfMissing = true)
class RetentionSchedule {
    private static final Logger log = LoggerFactory.getLogger(RetentionSchedule.class);
    private final RetentionJob job;

    RetentionSchedule(RetentionJob job) {
        this.job = job;
    }

    @Scheduled(cron = "${luma.retention.cron:0 30 2 * * *}", zone = "Asia/Colombo")
    void nightly() {
        try {
            job.runOnce();
        } catch (RuntimeException e) {
            log.error("Retention run failed: {}", e.getClass().getSimpleName());
        }
    }
}
