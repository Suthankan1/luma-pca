-- Zoom flow fixes:
-- * a stored report knows its webinar, so runs checked only from Zoom's report (no live events) can be listed;
-- * the host is marked in report rows (participant id = the webinar's host_id), so the teacher is never "unmatched";
-- * registrants are kept (registrant id → registration email), so someone who registered with the email LUMA
--   knows but joined from another Zoom account is still matched to their student record.

ALTER TABLE zoom_report_fetches ADD COLUMN webinar_id VARCHAR(40);
UPDATE zoom_report_fetches f SET webinar_id = coalesce(
  (SELECT l.webinar_id FROM zoom_occurrence_links l WHERE l.academy_id = f.academy_id AND l.occurrence_uuid = f.occurrence_uuid),
  (SELECT min(e.webinar_id) FROM zoom_occurrence_events e WHERE e.academy_id = f.academy_id AND e.occurrence_uuid = f.occurrence_uuid));

ALTER TABLE zoom_report_participants
  ADD COLUMN host BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN registrant_email VARCHAR(254),
  ADD CONSTRAINT ck_zoom_report_participants_registrant_email
    CHECK (registrant_email IS NULL OR (registrant_email <> '' AND registrant_email = lower(btrim(registrant_email))));
-- Hosts already known from live events.
UPDATE zoom_report_participants p SET host = TRUE
WHERE EXISTS (SELECT 1 FROM zoom_occurrence_events e WHERE e.academy_id = p.academy_id AND e.host AND e.participant_email = p.email);

CREATE TABLE zoom_registrants (
  academy_id UUID NOT NULL REFERENCES academies(id),
  webinar_id VARCHAR(40) NOT NULL,
  registrant_id VARCHAR(100) NOT NULL,
  email VARCHAR(254) NOT NULL,
  fetched_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_zoom_registrants PRIMARY KEY (academy_id, webinar_id, registrant_id),
  CONSTRAINT ck_zoom_registrants_email CHECK (email <> '' AND email = lower(btrim(email)))
);
CREATE INDEX ix_zoom_registrants_registrant ON zoom_registrants (academy_id, registrant_id);
