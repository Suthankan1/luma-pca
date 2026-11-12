package com.physicscube.luma.sessions;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that created a class or changed its time, webinar,
 * batches or status, after the change is flushed. Listeners (the Zoom module) run in the
 * same transaction, so their follow-up changes commit or roll back with it.
 *
 * @param before the class's Zoom slot before the change; {@code null} for a new class or one without a webinar
 * @param after the slot now; {@code null} when the class has no webinar
 * @param scheduledNow whether the class is scheduled (not cancelled) after the change
 */
public record ClassSessionChanged(UUID academyId, UUID sessionId, UUID actorAccountId, ZoomSlot before, ZoomSlot after,
                                  boolean scheduledNow) {
    /** Where Zoom events of the class are expected: its webinar and time. */
    public record ZoomSlot(String webinarId, Instant startsAt, Instant endsAt) {
    }
}
