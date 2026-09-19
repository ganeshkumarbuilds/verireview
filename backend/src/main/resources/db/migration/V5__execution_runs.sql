-- V5: Execution runs for APPLIED projects (Phase 10B)
-- Sandbox build+test evidence, never on host.
CREATE TABLE execution_runs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    patch_id    UUID        REFERENCES patches (id) ON DELETE SET NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_execution_runs_status CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILURE','TIMEOUT')),
    exit_code   INTEGER,
    stdout      TEXT,
    stderr      TEXT,
    duration_ms BIGINT      CONSTRAINT ck_execution_runs_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    build_status VARCHAR(20)
        CONSTRAINT ck_execution_runs_build_status CHECK (build_status IN ('PENDING','RUNNING','SUCCESS','FAILURE','TIMEOUT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_execution_runs_project ON execution_runs (project_id, created_at DESC);
CREATE INDEX ix_execution_runs_patch ON execution_runs (patch_id);
