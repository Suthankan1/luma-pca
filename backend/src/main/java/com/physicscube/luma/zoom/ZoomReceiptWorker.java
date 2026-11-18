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
    private final ZoomReceiptSignal signal;
    private final java.time.Duration idleInterval;
    private long lastRun;
    private boolean busy = true;

    ZoomReceiptWorker(ZoomReceiptProcessor processor, ZoomReceiptSignal signal,
                      @org.springframework.beans.factory.annotation.Value("${luma.zoom.processing.idle-interval:PT60S}") java.time.Duration idleInterval) {
        this.processor = processor;
        this.signal = signal;
        this.idleInterval = idleInterval;
    }

    /**
     * Every poll interval while receipts are arriving or being processed; otherwise once per idle interval (for retries
     * that become due), so an idle server does not query the database every few seconds.
     */
    @Scheduled(fixedDelayString = "${luma.zoom.processing.poll-interval:PT5S}")
    void poll() {
        long now = System.nanoTime();
        if (!signal.take() && !busy && now - lastRun < idleInterval.toNanos()) {
            return;
        }
        lastRun = now;
        try {
            busy = processor.runOnce() > 0;
        } catch (RuntimeException e) {
            busy = true;
            // Never let one bad poll stop the schedule; the receipt stays due.
            log.error("Zoom receipt poll failed: {}", e.getClass().getSimpleName());
        }
    }
}
