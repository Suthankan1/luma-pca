-- Slice 003D (part 2): who each Zoom participant is.
--
-- Field shapes were confirmed by the first real test webinar (13 Nov 2026, see
-- docs/integration/ZOOM-TEST-WEBINAR.md): on joined/left events payload.object.participant
-- carries participant_uuid and user_id (new on every rejoin, so they identify one visit),
-- registrant_id and email (the same across rejoins), user_name, and the host's
-- participant.id equals payload.object.host_id. IP addresses are deliberately not copied.

ALTER TABLE zoom_occurrence_events
  -- One visit (a join and its leave share it); NULL for webinar started/ended.
  ADD COLUMN visit_key VARCHAR(120),
  ADD COLUMN registrant_id VARCHAR(100),
  -- Lower-cased and trimmed; the key used to find the person in LUMA.
  ADD COLUMN participant_email VARCHAR(254),
  ADD COLUMN participant_name VARCHAR(200),
  ADD COLUMN host BOOLEAN NOT NULL DEFAULT FALSE,
  ADD CONSTRAINT ck_zoom_occurrence_events_email CHECK (participant_email IS NULL
    OR (participant_email <> '' AND participant_email = lower(btrim(participant_email))));
CREATE INDEX ix_zoom_occurrence_events_email ON zoom_occurrence_events (academy_id, participant_email)
  WHERE participant_email IS NOT NULL;

-- Events processed before this migration get the same fields from their stored receipts.
UPDATE zoom_occurrence_events e SET
  visit_key = left(coalesce(nullif(r.payload #>> '{payload,object,participant,participant_uuid}', ''),
                            'u:' || nullif(r.payload #>> '{payload,object,participant,user_id}', '')), 120),
  registrant_id = left(nullif(r.payload #>> '{payload,object,participant,registrant_id}', ''), 100),
  participant_email = left(nullif(lower(btrim(r.payload #>> '{payload,object,participant,email}')), ''), 254),
  participant_name = left(nullif(btrim(r.payload #>> '{payload,object,participant,user_name}'), ''), 200),
  host = coalesce(r.payload #>> '{payload,object,participant,id}' = r.payload #>> '{payload,object,host_id}', FALSE)
FROM zoom_webhook_receipts r
WHERE r.id = e.receipt_id
  AND e.event_type IN ('webinar.participant_joined', 'webinar.participant_left');

-- An administrator's decision about a Zoom email address: the student it belongs to, or,
-- with no student, "not a student" (a teacher, guest or test account). It applies to every
-- webinar and overrides the automatic match by a student's sign-in email.
CREATE TABLE zoom_identity_links (
  academy_id UUID NOT NULL REFERENCES academies(id),
  email VARCHAR(254) NOT NULL,
  student_id UUID,
  reason VARCHAR(300) NOT NULL,
  -- No foreign key: the decision's history must survive the account.
  linked_by UUID NOT NULL,
  linked_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_zoom_identity_links PRIMARY KEY (academy_id, email),
  CONSTRAINT fk_zoom_identity_links_student_same_academy
    FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT ck_zoom_identity_links_email CHECK (email <> '' AND email = lower(btrim(email))),
  CONSTRAINT ck_zoom_identity_links_reason CHECK (btrim(reason) <> '')
);
CREATE INDEX ix_zoom_identity_links_student ON zoom_identity_links (student_id) WHERE student_id IS NOT NULL;
