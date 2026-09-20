-- V12: Add VERIFIED status to generations check constraint.
-- Generation workflow now includes VERIFIED as a terminal state before COMPLETED.

ALTER TABLE generations DROP CONSTRAINT ck_generations_status;

ALTER TABLE generations ADD CONSTRAINT ck_generations_status CHECK (status IN (
  'DRAFT', 'READY', 'QUEUED', 'PLANNING', 'GENERATING', 'CODING',
  'BUILDING', 'REVIEWING', 'FIXING', 'REBUILDING', 'TESTING',
  'REVERIFYING', 'VERIFYING', 'VERIFIED', 'COMPLETED', 'FAILED', 'CANCELLED'));