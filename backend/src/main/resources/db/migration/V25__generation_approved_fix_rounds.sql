ALTER TABLE agent_executions
    ADD COLUMN IF NOT EXISTS round_number INTEGER NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS generation_fix_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    generation_id UUID NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    round_number INTEGER NOT NULL,
    selected_finding_ids TEXT NOT NULL,
    before_finding_count INTEGER NOT NULL,
    after_finding_count INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_generation_fix_approval_round CHECK (round_number BETWEEN 2 AND 5),
    CONSTRAINT ck_generation_fix_approval_status CHECK (status IN ('APPROVED', 'RUNNING', 'COMPLETED', 'FAILED', 'REVERTED'))
);

CREATE INDEX IF NOT EXISTS ix_generation_fix_approvals_generation_round
    ON generation_fix_approvals (generation_id, round_number, created_at DESC);

CREATE TABLE IF NOT EXISTS generation_file_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    generation_id UUID NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
    approval_id UUID NOT NULL REFERENCES generation_fix_approvals (id) ON DELETE CASCADE,
    round_number INTEGER NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    before_content TEXT NOT NULL,
    after_content TEXT NOT NULL,
    reverted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_generation_file_versions_round CHECK (round_number BETWEEN 2 AND 5)
);

CREATE INDEX IF NOT EXISTS ix_generation_file_versions_generation_round
    ON generation_file_versions (generation_id, round_number, created_at);
