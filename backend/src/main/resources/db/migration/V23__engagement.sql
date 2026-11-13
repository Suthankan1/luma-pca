-- Slices 006A–C: engagement from LUMA's own student portal, with consent.
--
-- Off by default. Zoom attendee emoji are anonymous (ZOOM-RULES 7), so every count here comes
-- from the portal and carries its source. Nothing is recorded for a student who has not opted in,
-- and an owner must switch engagement on (which the academy should do only after its privacy
-- questions, OPEN-QUESTIONS P0, are signed off).

CREATE TABLE engagement_settings (
  academy_id UUID PRIMARY KEY REFERENCES academies(id),
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  -- The wording students agree to; a new version asks everyone again.
  consent_text VARCHAR(2000) NOT NULL,
  consent_version INTEGER NOT NULL DEFAULT 1,
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_engagement_settings_text CHECK (btrim(consent_text) <> ''),
  CONSTRAINT ck_engagement_settings_version CHECK (consent_version >= 1)
);

-- A student's own choice, per consent version. Leaderboard visibility is a separate choice.
CREATE TABLE engagement_consents (
  academy_id UUID NOT NULL REFERENCES academies(id),
  student_id UUID NOT NULL,
  consent_version INTEGER NOT NULL,
  granted BOOLEAN NOT NULL,
  leaderboard_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
  decided_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_engagement_consents PRIMARY KEY (academy_id, student_id),
  CONSTRAINT fk_engagement_consents_student FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT ck_engagement_consents_leaderboard CHECK (granted OR NOT leaderboard_opt_in)
);

-- 006A: a reaction a student sends from the portal during a class.
CREATE TABLE portal_reactions (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  class_session_id UUID NOT NULL,
  student_id UUID NOT NULL,
  kind VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_portal_reactions_session FOREIGN KEY (class_session_id, academy_id) REFERENCES class_sessions (id, academy_id),
  CONSTRAINT fk_portal_reactions_student FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT ck_portal_reactions_kind CHECK (kind IN ('UNDERSTOOD', 'CONFUSED', 'SLOW_DOWN', 'SPEED_UP'))
);
CREATE INDEX ix_portal_reactions_session ON portal_reactions (class_session_id, created_at);
CREATE INDEX ix_portal_reactions_student ON portal_reactions (class_session_id, student_id, created_at);

-- 006B: a short learning question staff open during a class.
CREATE TABLE pulse_questions (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  class_session_id UUID NOT NULL,
  prompt VARCHAR(300) NOT NULL,
  options VARCHAR(100)[] NOT NULL,
  opened_by UUID NOT NULL,
  opened_at TIMESTAMPTZ NOT NULL,
  closes_at TIMESTAMPTZ NOT NULL,
  closed_early_at TIMESTAMPTZ,
  CONSTRAINT fk_pulse_questions_session FOREIGN KEY (class_session_id, academy_id) REFERENCES class_sessions (id, academy_id),
  CONSTRAINT uq_pulse_questions_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_pulse_questions_prompt CHECK (btrim(prompt) <> ''),
  CONSTRAINT ck_pulse_questions_options CHECK (cardinality(options) BETWEEN 2 AND 5),
  CONSTRAINT ck_pulse_questions_window CHECK (closes_at > opened_at AND closes_at <= opened_at + interval '10 minutes')
);
CREATE INDEX ix_pulse_questions_session ON pulse_questions (class_session_id, opened_at);

CREATE TABLE pulse_answers (
  question_id UUID NOT NULL,
  academy_id UUID NOT NULL,
  student_id UUID NOT NULL,
  option_index INTEGER NOT NULL,
  answered_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_pulse_answers PRIMARY KEY (question_id, student_id),
  CONSTRAINT fk_pulse_answers_question FOREIGN KEY (question_id, academy_id) REFERENCES pulse_questions (id, academy_id),
  CONSTRAINT fk_pulse_answers_student FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT ck_pulse_answers_option CHECK (option_index BETWEEN 0 AND 4)
);
