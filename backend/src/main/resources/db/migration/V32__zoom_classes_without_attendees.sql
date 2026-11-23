-- Historical reports use their persisted join count. New fetches also record whether the
-- parser had to discard incomplete source rows, which cannot prove that nobody attended.
ALTER TABLE zoom_report_fetches ADD COLUMN complete_rows BOOLEAN NOT NULL DEFAULT TRUE;

-- Read-only Zoom evidence for attendance consumers. One row per excluded class.
CREATE VIEW zoom_classes_without_attendees AS
SELECT l.academy_id, l.class_session_id
FROM zoom_occurrence_links l
LEFT JOIN zoom_report_fetches f
  ON f.academy_id = l.academy_id AND f.occurrence_uuid = l.occurrence_uuid
WHERE l.class_session_id IS NOT NULL
GROUP BY l.academy_id, l.class_session_id
HAVING bool_and(coalesce(f.status = 'FETCHED' AND f.complete_rows AND f.join_sessions >= 0
   AND f.join_sessions = (
     SELECT count(*) FROM zoom_report_participants p
     WHERE p.academy_id = l.academy_id AND p.occurrence_uuid = l.occurrence_uuid AND p.host
   ), false));
