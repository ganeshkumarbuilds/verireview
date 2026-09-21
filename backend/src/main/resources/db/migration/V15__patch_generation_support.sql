-- V15: Add generation support to patches for generation fix workflow.
-- Generation patches link to generation instead of project.
-- Exactly one owner: project_id XOR generation_id.

ALTER TABLE patches
  ADD COLUMN IF NOT EXISTS generation_id UUID REFERENCES generations (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_patches_generation ON patches (generation_id);

ALTER TABLE patches DROP CONSTRAINT IF EXISTS ck_patches_one_owner;

ALTER TABLE patches ADD CONSTRAINT ck_patches_one_owner
  CHECK (
    (project_id IS NOT NULL AND generation_id IS NULL AND fix_request_id IS NOT NULL)
    OR (project_id IS NULL AND generation_id IS NOT NULL AND fix_request_id IS NOT NULL)
  );