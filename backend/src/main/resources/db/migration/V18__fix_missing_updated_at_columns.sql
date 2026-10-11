-- V18: Add missing updated_at columns to tables that extend BaseEntity
-- Both CheckRunGate and ProjectHealthScore extend BaseEntity which requires
-- created_at and updated_at. V17 created check_run_gates with only created_at,
-- and project_health_scores with only last_computed_at (no created_at/updated_at).

-- check_run_gates: add updated_at (created_at already exists from V17)
ALTER TABLE check_run_gates
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- project_health_scores: add created_at and updated_at (last_computed_at already exists from V17)
-- Add columns as nullable first, backfill, then add NOT NULL + DEFAULT
ALTER TABLE project_health_scores
  ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

ALTER TABLE project_health_scores
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE project_health_scores
  SET created_at = COALESCE(created_at, last_computed_at, now()),
      updated_at = COALESCE(updated_at, last_computed_at, now());

ALTER TABLE project_health_scores
  ALTER COLUMN created_at SET DEFAULT now(),
  ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE project_health_scores
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN updated_at SET NOT NULL;