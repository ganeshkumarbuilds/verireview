-- V11: Allow verification_runs without patch_id for generation workflows.
-- Generation verification runs link to generation_id instead of patch_id.
-- Add check constraint: exactly one of patch_id or generation_id must be present.

ALTER TABLE verification_runs
  ALTER COLUMN patch_id DROP NOT NULL;

-- Ensure each verification run has either a patch (fix flow) or a generation (gen flow)
ALTER TABLE verification_runs
  ADD CONSTRAINT ck_verification_runs_source
  CHECK (
    (patch_id IS NOT NULL AND generation_id IS NULL) OR
    (patch_id IS NULL AND generation_id IS NOT NULL)
  );