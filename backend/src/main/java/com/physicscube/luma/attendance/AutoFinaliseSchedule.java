package com.physicscube.luma.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Looks for classes to finalise automatically every few minutes; only academies whose owner switched it on are affected. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "luma.attendance.auto-finalise.enabled", havingValue = "true", matchIfMissing = true)
class AutoFinaliseSchedule {
    private static final Logger log = LoggerFactory.getLogger(AutoFinaliseSchedule.class);
    private final AttendanceQueueService queue;

    AutoFinaliseSchedule(AttendanceQueueService queue) {
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${luma.attendance.auto-finalise.interval:PT5M}", initialDelayString = "PT2M")
    void run() {
        try {
            int done = queue.autoFinaliseOnce();
            if (done > 0) {
                log.info("Automatically finalised {} class(es)", done);
            }
        } catch (RuntimeException e) {
            log.error("Automatic finalisation failed: {}", e.getClass().getSimpleName());
        }
    }
}
