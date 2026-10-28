DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_findings_category'
    ) THEN
        ALTER TABLE findings DROP CONSTRAINT ck_findings_category;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_findings_category'
    ) THEN
        ALTER TABLE findings ADD CONSTRAINT ck_findings_category
        CHECK (category IN (
            'SYNTAX', 'LOGIC', 'BUG', 'SECURITY', 'CODE_QUALITY', 'PERFORMANCE',
            'ARCHITECTURE', 'DEPENDENCY', 'MISSING_TEST', 'STYLE'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS analysis_file_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    path VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ANALYZED', 'SKIPPED')),
    reason TEXT,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    finding_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analysis_file_results_review_path UNIQUE (review_id, path)
);

CREATE INDEX IF NOT EXISTS idx_analysis_file_results_review
    ON analysis_file_results(review_id);
