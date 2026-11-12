package com.physicscube.luma.sessions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A weekly series: the classes it makes (with ids once created) and any that would overlap
 * a scheduled class of the same batches. Nothing is created while there are conflicts.
 *
 * @param created true only when the classes were created; false for a preview or when there are conflicts
 */
public record SeriesResult(boolean created, UUID seriesId, List<Occurrence> occurrences, List<Conflict> conflicts) {
    public record Occurrence(LocalDate localDate, Instant startsAt, Instant endsAt, UUID id) {
    }

    public record Conflict(LocalDate localDate, UUID sessionId, String title, Instant startsAt, Instant endsAt) {
    }
}
