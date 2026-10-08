-- V17: Shared check runs model and unified findings schema
-- Implements: single status model for GENERATE, REVIEW, FIX, VERIFY
-- Adds tool_confirmed, confidence, rule, evidence snippet, category, explanation, suggested_fix to findings

-- Check Run: one row per feature execution (GENERATE, REVIEW, FIX, VERIFY)
CREATE TABLE check_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    generation_id UUID REFERENCES generations(id) ON DELETE SET NULL,
    feature VARCHAR(20) NOT NULL CHECK (feature IN ('GENERATE', 'REVIEW', 'FIX', 'VERIFY')),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    progress INT NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),
    current_step VARCHAR(500),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    severity_critical INT NOT NULL DEFAULT 0,
    severity_high INT NOT NULL DEFAULT 0,
    severity_medium INT NOT NULL DEFAULT 0,
    severity_low INT NOT NULL DEFAULT 0,
    severity_info INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_check_runs_project_feature_latest UNIQUE (project_id, feature, created_at)
);

CREATE INDEX idx_check_runs_project_feature ON check_runs(project_id, feature);
CREATE INDEX idx_check_runs_generation ON check_runs(generation_id);
CREATE INDEX idx_check_runs_status ON check_runs(status);

-- Check Step: ordered steps within a check run
CREATE TABLE check_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    check_run_id UUID NOT NULL REFERENCES check_runs(id) ON DELETE CASCADE,
    step_order INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    progress INT NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),
    current_message VARCHAR(500),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    log_tail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_check_steps_run_order UNIQUE (check_run_id, step_order)
);

CREATE INDEX idx_check_steps_run ON check_steps(check_run_id);

-- Extend findings with unified schema fields
ALTER TABLE findings ADD COLUMN IF NOT EXISTS rule VARCHAR(200);
ALTER TABLE findings ADD COLUMN IF NOT EXISTS evidence_snippet TEXT;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS explanation TEXT;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS suggested_fix TEXT;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS tool_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE findings ADD COLUMN IF NOT EXISTS analyzer VARCHAR(100);

-- Backfill analyzer from evidence JSON where possible
UPDATE findings SET analyzer = 'unknown' WHERE analyzer IS NULL;

-- Add index for tool_confirmed queries
CREATE INDEX idx_findings_tool_confirmed ON findings(tool_confirmed);

-- Check Run verification gates (for VERIFY feature)
CREATE TABLE check_run_gates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    check_run_id UUID NOT NULL REFERENCES check_runs(id) ON DELETE CASCADE,
    gate_name VARCHAR(100) NOT NULL,
    gate_description VARCHAR(500),
    passed BOOLEAN NOT NULL DEFAULT FALSE,
    evidence TEXT,
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_check_run_gates_run_name UNIQUE (check_run_id, gate_name)
);

CREATE INDEX idx_check_run_gates_run ON check_run_gates(check_run_id);

-- Project health score components (computed, stored for trending)
CREATE TABLE project_health_scores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    score INT NOT NULL CHECK (score >= 0 AND score <= 100),
    -- Component scores (each 0-100)
    generate_score INT NOT NULL DEFAULT 0,
    review_score INT NOT NULL DEFAULT 0,
    fix_score INT NOT NULL DEFAULT 0,
    verify_score INT NOT NULL DEFAULT 0,
    -- Raw metrics for transparency
    open_critical_high INT NOT NULL DEFAULT 0,
    open_findings_total INT NOT NULL DEFAULT 0,
    fixed_verified_ratio DOUBLE PRECISION,
    last_computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_health_scores_project_latest UNIQUE (project_id, last_computed_at)
);

CREATE INDEX idx_health_scores_project ON project_health_scores(project_id);

-- Enable pg_trgm for similarity searches if not already
CREATE EXTENSION IF NOT EXISTS pg_trgm;