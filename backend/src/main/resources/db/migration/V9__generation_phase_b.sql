-- V9: Phase B generation pipeline.
-- Persisted project plans (one per generation iteration) so a run can be
-- resumed and inspected. Agent executions may now exist before any project
-- does (planning/coding happen before materialization), so their project
-- link becomes nullable; review-flow executions always set it as before.

CREATE TABLE IF NOT EXISTS generation_plans (
  id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  generation_id UUID         NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
  iteration     INTEGER      NOT NULL CONSTRAINT ck_generation_plans_iteration CHECK (iteration >= 1),
  plan_json     TEXT         NOT NULL,
  file_count    INTEGER      NOT NULL CONSTRAINT ck_generation_plans_files CHECK (file_count >= 0),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uq_generation_plans_generation_iteration UNIQUE (generation_id, iteration)
);
CREATE INDEX IF NOT EXISTS ix_generation_plans_generation
  ON generation_plans (generation_id);

ALTER TABLE agent_executions ALTER COLUMN project_id DROP NOT NULL;
