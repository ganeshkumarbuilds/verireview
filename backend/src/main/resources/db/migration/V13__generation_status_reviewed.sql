-- V13: Add REVIEWED status to generations check constraint.
-- Generation workflow now includes REVIEWED after review phase.

ALTER TABLE generations DROP CONSTRAINT ck_generations_status;

ALTER TABLE generations ADD CONSTRAINT ck_generations_status CHECK (status IN (
  'DRAFT', 'READY', 'QUEUED', 'PLANNING', 'GENERATING', 'CODING',
  'BUILDING', 'REVIEWING', 'FIXING', 'REBUILDING', 'TESTING',
  'REVERIFYING', 'VERIFYING', 'VERIFIED', 'REVIEWED', 'COMPLETED', 'FAILED', 'CANCELLED'));