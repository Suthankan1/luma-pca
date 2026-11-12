-- Slice ZM-5: when a class is created or changed, undecided occurrences on its webinar are
-- matched again; this keeps that lookup off a full scan of the (large) events table.
CREATE INDEX ix_zoom_occurrence_events_webinar ON zoom_occurrence_events (academy_id, webinar_id, occurrence_uuid);
