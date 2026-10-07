-- V16: Import job tracking for async ZIP/GitHub imports.
-- Stores job status, progress, and error information.

CREATE TABLE import_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects (id) ON DELETE SET NULL,
    source_type VARCHAR(20) NOT NULL CHECK (source_type IN ('ZIP_UPLOAD', 'GITHUB')),
    name VARCHAR(200) NOT NULL,
    description VARCHAR(5000),
    language VARCHAR(100),
    github_url VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'EXTRACTING', 'INDEXING', 'DONE', 'FAILED')),
    files_processed BIGINT NOT NULL DEFAULT 0,
    files_total BIGINT NOT NULL DEFAULT 0,
    bytes_processed BIGINT NOT NULL DEFAULT 0,
    bytes_total BIGINT NOT NULL DEFAULT 0,
    current_step VARCHAR(500),
    error_message TEXT,
    staged_file_path VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT
);

CREATE INDEX IF NOT EXISTS ix_import_jobs_owner ON import_jobs (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_import_jobs_status ON import_jobs (status);
CREATE INDEX IF NOT EXISTS ix_import_jobs_project ON import_jobs (project_id);