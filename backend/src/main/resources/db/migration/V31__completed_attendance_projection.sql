-- Rebuildable matched Zoom facts. Official records and enrolments remain their own sources.
CREATE TABLE attendance_projection_jobs (
  academy_id uuid NOT NULL,
  class_session_id uuid NOT NULL,
  source_revision bigint NOT NULL DEFAULT 0,
  published_revision bigint,
  published_identity_revision bigint,
  next_attempt_at timestamptz,
  PRIMARY KEY (academy_id, class_session_id),
  FOREIGN KEY (class_session_id, academy_id) REFERENCES class_sessions (id, academy_id) ON DELETE CASCADE
);

CREATE TABLE attendance_projection_identity (
  academy_id uuid PRIMARY KEY REFERENCES academies (id) ON DELETE CASCADE,
  revision bigint NOT NULL
);

CREATE TABLE attendance_projected_visits (
  academy_id uuid NOT NULL,
  class_session_id uuid NOT NULL,
  student_id uuid NOT NULL,
  visit_count integer NOT NULL CHECK (visit_count > 0),
  connected_seconds bigint NOT NULL CHECK (connected_seconds >= 0),
  PRIMARY KEY (academy_id, class_session_id, student_id),
  FOREIGN KEY (academy_id, class_session_id) REFERENCES attendance_projection_jobs (academy_id, class_session_id) ON DELETE CASCADE,
  FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id) ON DELETE CASCADE
);
CREATE INDEX ix_attendance_projected_visits_student ON attendance_projected_visits (academy_id, student_id);

-- Every class has a marker before its source can be published. Initial backfill and new class inserts share this invariant.
LOCK TABLE class_sessions IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO attendance_projection_jobs (academy_id, class_session_id) SELECT academy_id, id FROM class_sessions;

CREATE FUNCTION attendance_projection_session_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO attendance_projection_jobs (academy_id, class_session_id) VALUES (NEW.academy_id, NEW.id)
  ON CONFLICT (academy_id, class_session_id) DO UPDATE
    SET source_revision = attendance_projection_jobs.source_revision + 1, next_attempt_at = NULL;
  RETURN NULL;
END;
$$;
CREATE TRIGGER attendance_projection_session_changed
AFTER INSERT OR UPDATE OF starts_at, ends_at, status ON class_sessions
FOR EACH ROW EXECUTE FUNCTION attendance_projection_session_changed();

CREATE FUNCTION attendance_projection_invalidate_class(academy uuid, session_id uuid) RETURNS void LANGUAGE sql AS $$
  UPDATE attendance_projection_jobs SET source_revision = source_revision + 1, next_attempt_at = NULL
  WHERE academy_id = academy AND class_session_id = session_id;
$$;

CREATE FUNCTION attendance_projection_link_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    PERFORM attendance_projection_invalidate_class(OLD.academy_id, OLD.class_session_id);
  END IF;
  IF TG_OP <> 'DELETE' THEN
    PERFORM attendance_projection_invalidate_class(NEW.academy_id, NEW.class_session_id);
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER attendance_projection_link_changed AFTER INSERT OR UPDATE OR DELETE ON zoom_occurrence_links
FOR EACH ROW EXECUTE FUNCTION attendance_projection_link_changed();

CREATE FUNCTION attendance_projection_reports_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE attendance_projection_jobs job SET source_revision = job.source_revision + 1, next_attempt_at = NULL
  FROM (SELECT DISTINCT link.academy_id, link.class_session_id
        FROM projection_changed changed
        JOIN zoom_occurrence_links link USING (academy_id, occurrence_uuid)
        WHERE link.class_session_id IS NOT NULL) affected
  WHERE job.academy_id = affected.academy_id AND job.class_session_id = affected.class_session_id;
  RETURN NULL;
END;
$$;

-- One invalidation per linked class for a batch insert, replacement, or erasure of report participants.
DO $$
DECLARE source_table text;
BEGIN
  FOREACH source_table IN ARRAY ARRAY['zoom_report_fetches', 'zoom_report_participants'] LOOP
    EXECUTE format('CREATE TRIGGER attendance_projection_report_insert AFTER INSERT ON %I
      REFERENCING NEW TABLE AS projection_changed FOR EACH STATEMENT EXECUTE FUNCTION attendance_projection_reports_changed()', source_table);
    EXECUTE format('CREATE TRIGGER attendance_projection_report_delete AFTER DELETE ON %I
      REFERENCING OLD TABLE AS projection_changed FOR EACH STATEMENT EXECUTE FUNCTION attendance_projection_reports_changed()', source_table);
    EXECUTE format('CREATE TRIGGER attendance_projection_report_update_old AFTER UPDATE ON %I
      REFERENCING OLD TABLE AS projection_changed FOR EACH STATEMENT EXECUTE FUNCTION attendance_projection_reports_changed()', source_table);
    EXECUTE format('CREATE TRIGGER attendance_projection_report_update_new AFTER UPDATE ON %I
      REFERENCING NEW TABLE AS projection_changed FOR EACH STATEMENT EXECUTE FUNCTION attendance_projection_reports_changed()', source_table);
  END LOOP;
END;
$$;

-- Once all reports are fetched, the source matcher only uses provisional events to exclude hosts.
CREATE FUNCTION attendance_projection_host_event_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' AND OLD.host THEN
    PERFORM attendance_projection_invalidate_class(link.academy_id, link.class_session_id)
    FROM zoom_occurrence_links link WHERE link.academy_id = OLD.academy_id AND link.occurrence_uuid = OLD.occurrence_uuid;
  END IF;
  IF TG_OP <> 'DELETE' AND NEW.host THEN
    PERFORM attendance_projection_invalidate_class(link.academy_id, link.class_session_id)
    FROM zoom_occurrence_links link WHERE link.academy_id = NEW.academy_id AND link.occurrence_uuid = NEW.occurrence_uuid;
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER attendance_projection_host_event_changed AFTER INSERT OR UPDATE OR DELETE ON zoom_occurrence_events
FOR EACH ROW EXECUTE FUNCTION attendance_projection_host_event_changed();

-- A matching decision or sign-in email change invalidates all snapshots for that academy without locking its class jobs.
CREATE FUNCTION attendance_projection_identity_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE academy uuid;
BEGIN
  academy := CASE WHEN TG_OP = 'DELETE' THEN OLD.academy_id ELSE NEW.academy_id END;
  INSERT INTO attendance_projection_identity (academy_id, revision) VALUES (academy, 1)
  ON CONFLICT (academy_id) DO UPDATE SET revision = attendance_projection_identity.revision + 1;
  RETURN NULL;
END;
$$;
CREATE TRIGGER attendance_projection_zoom_identity_changed AFTER INSERT OR UPDATE OR DELETE ON zoom_identity_links
FOR EACH ROW EXECUTE FUNCTION attendance_projection_identity_changed();
CREATE TRIGGER attendance_projection_student_identity_changed AFTER INSERT OR UPDATE OF account_id ON students
FOR EACH ROW EXECUTE FUNCTION attendance_projection_identity_changed();
CREATE TRIGGER attendance_projection_email_changed AFTER UPDATE OF email ON accounts
FOR EACH ROW WHEN (OLD.email IS DISTINCT FROM NEW.email) EXECUTE FUNCTION attendance_projection_identity_changed();
