-- V14: Allow reviews to belong to either a project OR a generation.
-- Generation reviews have generation_id set and project_id = NULL.
-- Project reviews have project_id set and generation_id = NULL.
-- Exactly one owner is enforced by CHECK constraint.

ALTER TABLE reviews ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE reviews ADD CONSTRAINT ck_reviews_one_owner
  CHECK (
    (project_id IS NOT NULL AND generation_id IS NULL)
    OR (project_id IS NULL AND generation_id IS NOT NULL)
  );