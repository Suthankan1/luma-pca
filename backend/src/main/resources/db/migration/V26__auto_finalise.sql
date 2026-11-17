-- Automatic finalisation (owner's choice): once Zoom's report is in for every linked run of an ended class, LUMA
-- finalises it by the recorded rule. auto_finalise_by is the owner who switched it on; records name that owner as
-- finaliser and the audit entry says it was automatic.
ALTER TABLE attendance_policies
  ADD COLUMN auto_finalise BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN auto_finalise_by UUID;
