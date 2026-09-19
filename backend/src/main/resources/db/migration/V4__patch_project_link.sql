-- V4: Phase 9C patch foundation - explicit project link for audit and ownership checks.
-- Additive only: adds nullable project_id FK so existing rows remain valid; application code populates it on creation.
ALTER TABLE patches ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS ix_patches_project ON patches (project_id);
