-- V22: Generate wizard configuration, honest sandbox-skip states, and
-- restructure proposals.
--
-- 1) generations stores the explicit auth choice and the selected
--    integrations. Integrations are scope choices only; no provider
--    credentials are ever stored here (see GenerationIntegration).
-- 2) generation_execution_evidence gains SKIPPED so a build/test that never
--    ran (Docker sandbox disabled) is never recorded as SUCCESS.
-- 3) agent_executions gains SKIPPED so the Verify pipeline step can say
--    "Not verified" instead of inventing COMPLETED.
-- 4) generations gains the NOT_VERIFIED lifecycle state: the pipeline can
--    still reach REVIEWED (code review), but the download gate stays locked.
-- 5) generation_restructure_proposals stores the proposed move plan, its
--    approval status, and the backup reference used by Revert.

ALTER TABLE generations ADD COLUMN IF NOT EXISTS auth_type VARCHAR(30) NOT NULL DEFAULT 'NONE';
ALTER TABLE generations ADD COLUMN IF NOT EXISTS integrations TEXT NOT NULL DEFAULT '';

ALTER TABLE generation_execution_evidence DROP CONSTRAINT IF EXISTS ck_gen_exec_evidence_build;
ALTER TABLE generation_execution_evidence ADD CONSTRAINT ck_gen_exec_evidence_build
  CHECK (build_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT', 'SKIPPED'));

ALTER TABLE generation_execution_evidence DROP CONSTRAINT IF EXISTS ck_gen_exec_evidence_test;
ALTER TABLE generation_execution_evidence ADD CONSTRAINT ck_gen_exec_evidence_test
  CHECK (test_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT', 'NOT_APPLICABLE', 'SKIPPED'));

ALTER TABLE agent_executions DROP CONSTRAINT IF EXISTS ck_agent_executions_status;
ALTER TABLE agent_executions ADD CONSTRAINT ck_agent_executions_status
  CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED'));

ALTER TABLE generations DROP CONSTRAINT IF EXISTS ck_generations_status;
ALTER TABLE generations ADD CONSTRAINT ck_generations_status CHECK (status IN (
  'DRAFT', 'READY', 'QUEUED', 'PLANNING', 'GENERATING', 'CODING',
  'BUILDING', 'REVIEWING', 'FIXING', 'REBUILDING', 'TESTING',
  'REVERIFYING', 'VERIFYING', 'NOT_VERIFIED', 'VERIFIED', 'REVIEWED', 'COMPLETED',
  'FAILED', 'CANCELLED'));

CREATE TABLE IF NOT EXISTS generation_restructure_proposals (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    generation_id   UUID          NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
    iteration       INTEGER       NOT NULL CONSTRAINT ck_restructure_iteration CHECK (iteration >= 1),
    preset          VARCHAR(30)   NOT NULL
        CONSTRAINT ck_restructure_preset CHECK (preset IN ('MVC', 'LAYERED', 'FEATURE_FOLDERS', 'CUSTOM')),
    description     TEXT,
    proposal_json   TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PROPOSED'
        CONSTRAINT ck_restructure_status CHECK (status IN ('PROPOSED', 'APPLIED', 'REVERTED', 'REJECTED')),
    backup_ref      VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_restructure_generation
  ON generation_restructure_proposals (generation_id, created_at DESC);
