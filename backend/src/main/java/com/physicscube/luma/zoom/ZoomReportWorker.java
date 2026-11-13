package com.physicscube.luma.zoom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Fetches due participant reports in the API process; does nothing unless the Zoom API is configured. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "luma.zoom.reports.enabled", havingValue = "true", matchIfMissing = true)
class ZoomReportWorker {
    private static final Logger log = LoggerFactory.getLogger(ZoomReportWorker.class);
    private final ZoomReportService reports;

    ZoomReportWorker(ZoomReportService reports) {
        this.reports = reports;
    }

    @Scheduled(fixedDelayString = "${luma.zoom.reports.poll-interval:PT5M}", initialDelayString = "PT1M")
    void poll() {
        try {
            reports.runOnce();
        } catch (RuntimeException e) {
            log.error("Zoom report poll failed: {}", e.getClass().getSimpleName());
        }
    }
}
