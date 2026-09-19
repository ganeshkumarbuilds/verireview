-- V1: VeriReview initial schema (Phase 2).
-- Hand-written per docs/DATABASE_DESIGN.md. Flyway owns all schema evolution;
-- Hibernate uses ddl-auto=validate only. Additive changes only from here on.

-- gen_random_uuid() is a core PostgreSQL function (no extension required).

CREATE TABLE roles (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE projects (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    source_type VARCHAR(20)  NOT NULL
        CONSTRAINT ck_projects_source_type
        CHECK (source_type IN ('ZIP_UPLOAD', 'PASTE', 'GITHUB', 'GENERATED')),
    language    VARCHAR(50),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_projects_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    storage_ref VARCHAR(500),
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_projects_owner_name UNIQUE (owner_id, name)
);
CREATE INDEX ix_projects_owner_created ON projects (owner_id, created_at DESC);

CREATE TABLE repositories (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID          NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    repo_url    VARCHAR(2000) NOT NULL,
    branch      VARCHAR(200),
    commit_sha  VARCHAR(64),
    imported_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_files (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID           NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    path        VARCHAR(1000)  NOT NULL,
    language    VARCHAR(50),
    size_bytes  BIGINT         NOT NULL CONSTRAINT ck_project_files_size CHECK (size_bytes >= 0),
    sha256      VARCHAR(64)   NOT NULL,
    content     TEXT,
    content_ref VARCHAR(1000),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_files_project_path UNIQUE (project_id, path),
    -- Exactly one inline body or external reference (DATABASE_DESIGN §4).
    CONSTRAINT ck_project_files_body CHECK ((content IS NULL) <> (content_ref IS NULL))
);
CREATE INDEX ix_project_files_project_path ON project_files (project_id, path);

CREATE TABLE reviews (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
        CONSTRAINT ck_reviews_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    duration_ms   BIGINT      CONSTRAINT ck_reviews_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    finding_count INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_reviews_finding_count CHECK (finding_count >= 0),
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reviews_project_created ON reviews (project_id, created_at DESC);

CREATE TABLE findings (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id   UUID          NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    category    VARCHAR(20)   NOT NULL
        CONSTRAINT ck_findings_category CHECK (category IN (
            'BUG', 'SECURITY', 'CODE_QUALITY', 'PERFORMANCE',
            'ARCHITECTURE', 'DEPENDENCY', 'MISSING_TEST', 'STYLE')),
    severity    VARCHAR(20)   NOT NULL
        CONSTRAINT ck_findings_severity
        CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
    source      VARCHAR(20)   NOT NULL
        CONSTRAINT ck_findings_source
        CHECK (source IN ('DETERMINISTIC', 'AI', 'VERIFIED')),
    status      VARCHAR(20)   NOT NULL DEFAULT 'OPEN'
        CONSTRAINT ck_findings_status CHECK (status IN (
            'OPEN', 'FIX_REQUESTED', 'FIX_PROPOSED',
            'VERIFIED_FIXED', 'REJECTED', 'WONTFIX')),
    title       VARCHAR(500)  NOT NULL,
    description TEXT,
    file_path   VARCHAR(1000),
    line_start  INTEGER       CONSTRAINT ck_findings_line_start CHECK (line_start IS NULL OR line_start >= 0),
    line_end    INTEGER       CONSTRAINT ck_findings_line_end CHECK (line_end IS NULL OR line_end >= 0),
    evidence    JSONB,
    dedup_key   VARCHAR(128),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_findings_review_filter ON findings (review_id, severity, status, category);
CREATE INDEX ix_findings_dedup ON findings (dedup_key);

CREATE TABLE fix_requests (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_id   UUID        NOT NULL REFERENCES findings (id) ON DELETE CASCADE,
    requested_by UUID        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_fix_requests_status CHECK (status IN (
            'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    scope_note   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_fix_requests_finding_status ON fix_requests (finding_id, status);
-- Approval gate: at most one open fix request per finding (DATABASE_DESIGN §3).
CREATE UNIQUE INDEX uq_fix_requests_one_open_per_finding ON fix_requests (finding_id)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

CREATE TABLE patches (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    fix_request_id   UUID        NOT NULL REFERENCES fix_requests (id) ON DELETE CASCADE,
    diff             TEXT        NOT NULL,
    files_changed    INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_patches_files CHECK (files_changed >= 0),
    additions        INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_patches_add CHECK (additions >= 0),
    deletions        INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_patches_del CHECK (deletions >= 0),
    status           VARCHAR(20) NOT NULL DEFAULT 'PROPOSED'
        CONSTRAINT ck_patches_status CHECK (status IN ('PROPOSED', 'APPLIED', 'REJECTED')),
    validation_error TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_patches_fix_request ON patches (fix_request_id);

CREATE TABLE verification_runs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NOT NULL patch link: no verdict without evidence (DATABASE_DESIGN §3).
    patch_id        UUID        NOT NULL REFERENCES patches (id) ON DELETE CASCADE,
    build_status    VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_verification_runs_build CHECK (build_status IN (
            'PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT')),
    tests_total     INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_runs_total CHECK (tests_total >= 0),
    tests_passed    INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_runs_passed CHECK (tests_passed >= 0),
    tests_failed    INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_runs_failed CHECK (tests_failed >= 0),
    tests_skipped   INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_runs_skipped CHECK (tests_skipped >= 0),
    static_delta    JSONB,
    security_delta  JSONB,
    verdict         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_verification_runs_verdict
        CHECK (verdict IN ('PENDING', 'VERIFIED', 'REJECTED')),
    log_ref         VARCHAR(1000),
    duration_ms     BIGINT      CONSTRAINT ck_runs_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_verification_runs_patch ON verification_runs (patch_id);

CREATE TABLE test_results (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    verification_run_id UUID         NOT NULL REFERENCES verification_runs (id) ON DELETE CASCADE,
    suite               VARCHAR(500) NOT NULL,
    test_name           VARCHAR(500) NOT NULL,
    status              VARCHAR(20)  NOT NULL
        CONSTRAINT ck_test_results_status
        CHECK (status IN ('PASSED', 'FAILED', 'SKIPPED', 'ERROR')),
    duration_ms         BIGINT       CONSTRAINT ck_test_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    message             TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_test_results_run_status ON test_results (verification_run_id, status);

CREATE TABLE agent_executions (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    agent_type          VARCHAR(20)   NOT NULL
        CONSTRAINT ck_agent_executions_type
        CHECK (agent_type IN ('PLANNING', 'REVIEW', 'CODING', 'VERIFIED')),
    model               VARCHAR(200)  NOT NULL,
    prompt_version      VARCHAR(50)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_agent_executions_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    input_hash          VARCHAR(64)   NOT NULL,
    output_ref          VARCHAR(1000),
    duration_ms         BIGINT        CONSTRAINT ck_agent_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    error               TEXT,
    review_id           UUID          REFERENCES reviews (id) ON DELETE SET NULL,
    fix_request_id      UUID          REFERENCES fix_requests (id) ON DELETE SET NULL,
    verification_run_id UUID          REFERENCES verification_runs (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_agent_executions_project ON agent_executions (project_id, agent_type, created_at DESC);

-- Append-only audit trail: no updated_at, no cascading deletes of the trail.
CREATE TABLE audit_logs (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    UUID          REFERENCES users (id) ON DELETE SET NULL,
    action      VARCHAR(100)  NOT NULL,
    entity_type VARCHAR(100)  NOT NULL,
    entity_id   VARCHAR(100)  NOT NULL,
    metadata    JSONB,
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_actor_created ON audit_logs (actor_id, created_at DESC);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
