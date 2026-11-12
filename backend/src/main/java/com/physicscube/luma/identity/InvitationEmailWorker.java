package com.physicscube.luma.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls for queued invitation emails inside the API process; safe on several instances. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "luma.mail.bulk.enabled", havingValue = "true", matchIfMissing = true)
class InvitationEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(InvitationEmailWorker.class);
    private final InvitationEmailSender sender;

    InvitationEmailWorker(InvitationEmailSender sender) {
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${luma.mail.bulk.poll-interval:PT5S}")
    void poll() {
        try {
            sender.runOnce();
        } catch (RuntimeException e) {
            // A bad poll must not stop the schedule; queued emails stay due.
            log.error("Invitation email poll failed: {}", e.getClass().getSimpleName());
        }
    }
}
