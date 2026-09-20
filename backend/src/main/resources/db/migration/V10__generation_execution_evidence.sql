-- V10: Generation execution evidence (Phase C).
-- Stores build/test evidence from sandboxed execution of a generation iteration.
-- Linked to generation + iteration; NOT to a project (materialization happens later).

CREATE TABLE IF NOT EXISTS generation_execution_evidence (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    generation_id       UUID         NOT NULL REFERENCES generations (id) ON DELETE CASCADE,
    iteration           INTEGER      NOT NULL CONSTRAINT ck_gen_exec_evidence_iter CHECK (iteration >= 1),
    command             TEXT         NOT NULL,
    exit_code           INTEGER      NOT NULL,
    duration_ms         BIGINT       NOT NULL CONSTRAINT ck_gen_exec_evidence_dur CHECK (duration_ms >= 0),
    stdout              TEXT,
    stderr              TEXT,
    build_status        VARCHAR(20)  NOT NULL
        CONSTRAINT ck_gen_exec_evidence_build CHECK (build_status IN (
            'PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT')),
    test_status         VARCHAR(20)  NOT NULL
        CONSTRAINT ck_gen_exec_evidence_test CHECK (test_status IN (
            'PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT', 'NOT_APPLICABLE')),
    failure_reason      VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_gen_exec_evidence_generation_iteration UNIQUE (generation_id, iteration)
);
CREATE INDEX IF NOT EXISTS ix_generation_execution_evidence_generation
    ON generation_execution_evidence (generation_id);