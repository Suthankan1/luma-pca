-- Slice 003D (part 1): which class session each Zoom webinar occurrence belongs to.

-- The Zoom webinar ID a class is held on. Recurring webinars reuse one ID across
-- many classes; the occurrence UUID tells the instances apart.
ALTER TABLE class_sessions
  ADD COLUMN zoom_webinar_id VARCHAR(20),
  ADD CONSTRAINT ck_class_sessions_zoom_webinar_id CHECK (zoom_webinar_id IS NULL OR zoom_webinar_id ~ '^[0-9]{9,20}$');
CREATE INDEX ix_class_sessions_zoom_webinar ON class_sessions (academy_id, zoom_webinar_id) WHERE zoom_webinar_id IS NOT NULL;

-- One decision per occurrence. AUTOMATIC links are made by the matcher; MANUAL rows
-- are an administrator's decision and are never changed automatically. A MANUAL row
-- without a session means "not a class" (for example a test webinar).
CREATE TABLE zoom_occurrence_links (
  academy_id UUID NOT NULL REFERENCES academies(id),
  occurrence_uuid VARCHAR(100) NOT NULL,
  webinar_id VARCHAR(40) NOT NULL,
  class_session_id UUID,
  link_source VARCHAR(20) NOT NULL,
  -- No foreign key: the decision's history must survive the account.
  linked_by UUID,
  linked_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_zoom_occurrence_links PRIMARY KEY (academy_id, occurrence_uuid),
  CONSTRAINT fk_zoom_occurrence_links_session_same_academy
    FOREIGN KEY (class_session_id, academy_id) REFERENCES class_sessions (id, academy_id),
  CONSTRAINT ck_zoom_occurrence_links_source CHECK (link_source IN ('AUTOMATIC', 'MANUAL')),
  CONSTRAINT ck_zoom_occurrence_links_automatic CHECK (link_source = 'MANUAL' OR (class_session_id IS NOT NULL AND linked_by IS NULL)),
  CONSTRAINT ck_zoom_occurrence_links_manual CHECK (link_source = 'AUTOMATIC' OR linked_by IS NOT NULL)
);
CREATE INDEX ix_zoom_occurrence_links_session ON zoom_occurrence_links (class_session_id);
