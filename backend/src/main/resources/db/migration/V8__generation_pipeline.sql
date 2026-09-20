-- V8: Generation pipeline domain (Phase A).
-- Task revisions, verified-artifact metadata, fix-loop iteration budget,
-- and nullable links from agent executions / verification runs / reviews
-- back to their generation. Secrets are NEVER persisted: none of these
-- tables have password / api-key columns, by design.

CREATE TABLE IF NOT EXISTS generation_revisions (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  generation_id   UUID         NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
  revision_number INTEGER      NOT NULL,
  requirement     TEXT         NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uq_generation_revisions_generation_number UNIQUE (generation_id, revision_number)
);
CREATE INDEX IF NOT EXISTS ix_generation_revisions_generation
  ON generation_revisions (generation_id);

CREATE TABLE IF NOT EXISTS generation_artifacts (
  id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  generation_id UUID         NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
  iteration     INTEGER      NOT NULL,
  file_count    INTEGER      NOT NULL CONSTRAINT ck_generation_artifacts_files CHECK (file_count >= 0),
  total_chars   BIGINT       NOT NULL CONSTRAINT ck_generation_artifacts_chars CHECK (total_chars >= 0),
  sha256        VARCHAR(64)  NOT NULL,
  storage_ref   VARCHAR(1000) NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_generation_artifacts_generation
  ON generation_artifacts (generation_id);

ALTER TABLE generations ADD COLUMN IF NOT EXISTS iteration INTEGER NOT NULL DEFAULT 0;
ALTER TABLE generations ADD COLUMN IF NOT EXISTS max_iterations INTEGER NOT NULL DEFAULT 5;

ALTER TABLE generations ADD CONSTRAINT ck_generations_status CHECK (status IN (
  'DRAFT', 'READY', 'QUEUED', 'PLANNING', 'GENERATING', 'CODING',
  'BUILDING', 'REVIEWING', 'FIXING', 'REBUILDING', 'TESTING',
  'REVERIFYING', 'VERIFYING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE agent_executions
  ADD COLUMN IF NOT EXISTS generation_id UUID REFERENCES generations (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS ix_agent_executions_generation
  ON agent_executions (generation_id);

ALTER TABLE verification_runs
  ADD COLUMN IF NOT EXISTS generation_id UUID REFERENCES generations (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_verification_runs_generation
  ON verification_runs (generation_id);

ALTER TABLE reviews
  ADD COLUMN IF NOT EXISTS generation_id UUID REFERENCES generations (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_reviews_generation ON reviews (generation_id);
