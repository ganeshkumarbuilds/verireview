-- V21: allow the SKIPPED build status on verification_runs.
--
-- A verification whose execution was skipped (Docker sandbox disabled) must be
-- able to record that fact instead of being forced into a misleading SUCCESS.

ALTER TABLE verification_runs DROP CONSTRAINT IF EXISTS ck_verification_runs_build;
ALTER TABLE verification_runs ADD CONSTRAINT ck_verification_runs_build
  CHECK (build_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT', 'SKIPPED'));
