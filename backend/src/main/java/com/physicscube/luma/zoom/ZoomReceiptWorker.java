package com.physicscube.luma.zoom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls for due receipts inside the API process. Safe to run on several instances at
 * once. A separately scaled worker and SQS transport are the production plan
 * (ADR-0006); the processor does not change for them.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "luma.zoom.processing.enabled", havingValue = "true", matchIfMissing = true)
class ZoomReceiptWorker {
    private static final Logger log = LoggerFactory.getLogger(ZoomReceiptWorker.class);
    private final ZoomReceiptProcessor processor;

    ZoomReceiptWorker(ZoomReceiptProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${luma.zoom.processing.poll-interval:PT5S}")
    void poll() {
        try {
            processor.runOnce();
        } catch (RuntimeException e) {
            // Never let one bad poll stop the schedule; the receipt stays due.
            log.error("Zoom receipt poll failed: {}", e.getClass().getSimpleName());
        }
    }
}
