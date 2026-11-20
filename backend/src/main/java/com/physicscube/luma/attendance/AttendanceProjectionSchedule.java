package com.physicscube.luma.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "luma.attendance.projection.enabled", havingValue = "true")
class AttendanceProjectionSchedule {
    private static final Logger log = LoggerFactory.getLogger(AttendanceProjectionSchedule.class);
    private final AttendanceProjectionStore store;
    private final ProvisionalAttendanceService attendance;

    AttendanceProjectionSchedule(AttendanceProjectionStore store, ProvisionalAttendanceService attendance) {
        this.store = store;
        this.attendance = attendance;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT1M")
    void rebuild() {
        for (var job : store.claim(8)) {
            try {
                var visits = attendance.projectableVisits(job.academyId(), job.window());
                if (visits != null) store.publish(job, visits);
            } catch (RuntimeException failure) {
                log.warn("Attendance projection rebuild will retry ({})", failure.getClass().getSimpleName());
            }
        }
    }
}
