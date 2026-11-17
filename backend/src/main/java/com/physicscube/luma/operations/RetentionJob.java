package com.physicscube.luma.operations;

import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the academy's retention settings nightly. Raw Zoom payloads keep participant names and
 * emails only as long as needed to replay failed events: after {@code zoom-payload-days} the
 * processed ones lose them (the extracted facts used by attendance stay). Engagement reactions and
 * answers older than {@code engagement-days} are deleted when that is set (0 keeps them).
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final int zoomPayloadDays;
    private final int engagementDays;

    RetentionJob(JdbcTemplate jdbc, @Value("${luma.retention.zoom-payload-days:180}") int zoomPayloadDays,
                 @Value("${luma.retention.engagement-days:0}") int engagementDays) {
        if (zoomPayloadDays < 1 || engagementDays < 0) {
            throw new IllegalArgumentException("luma.retention: zoom-payload-days must be at least 1 and engagement-days 0 or more");
        }
        this.jdbc = jdbc;
        this.zoomPayloadDays = zoomPayloadDays;
        this.engagementDays = engagementDays;
    }


    /** @return rows changed (payloads scrubbed + engagement rows deleted) */
    @Transactional
    public int runOnce() {
        Instant now = DbTime.now();
        int scrubbed = jdbc.update("""
                UPDATE zoom_webhook_receipts SET payload = payload #- '{payload,object,participant,email}' #- '{payload,object,participant,user_name}'
                WHERE processing_status = 'PROCESSED' AND received_at < ?
                  AND (payload #>> '{payload,object,participant,email}' IS NOT NULL OR payload #>> '{payload,object,participant,user_name}' IS NOT NULL)
                """, Timestamp.from(now.minus(Duration.ofDays(zoomPayloadDays))));
        int deleted = 0;
        if (engagementDays > 0) {
            Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(engagementDays)));
            deleted += jdbc.update("DELETE FROM portal_reactions WHERE created_at < ?", cutoff);
            deleted += jdbc.update("DELETE FROM pulse_answers WHERE answered_at < ?", cutoff);
        }
        if (scrubbed + deleted > 0) {
            log.info("Retention: {} Zoom payloads scrubbed, {} engagement rows deleted", scrubbed, deleted);
        }
        return scrubbed + deleted;
    }
}
