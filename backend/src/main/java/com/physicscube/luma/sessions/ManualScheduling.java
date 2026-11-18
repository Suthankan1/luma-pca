package com.physicscube.luma.sessions;

import com.physicscube.luma.shared.api.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Academy decision 2026-11-18: Zoom owns webinar schedules, so LUMA no longer creates, edits, moves or cancels classes,
 * weekly series or Zoom imports; classes come from monitored webinars' actual runs. Off by default; it can be switched on
 * ({@code LUMA_MANUAL_SCHEDULING_ENABLED}) only for test setups that build history directly. Reading classes, attendance
 * and history is unaffected.
 */
@Component
public class ManualScheduling {
    private final boolean enabled;

    ManualScheduling(@Value("${luma.sessions.manual-scheduling:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void require() {
        if (!enabled) {
            throw new ConflictException("Zoom owns webinar schedules: monitor the webinar by its ID or link under Webinars "
                    + "instead of scheduling or editing classes in LUMA");
        }
    }
}
