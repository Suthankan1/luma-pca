-- Slice SES-2: classes created together as a weekly series share a series id. Each class
-- stays an ordinary record (edited or cancelled on its own); the id only groups them, for
-- example to cancel the rest of a series.
ALTER TABLE class_sessions ADD COLUMN series_id UUID;
CREATE INDEX ix_class_sessions_series ON class_sessions (academy_id, series_id, starts_at) WHERE series_id IS NOT NULL;
