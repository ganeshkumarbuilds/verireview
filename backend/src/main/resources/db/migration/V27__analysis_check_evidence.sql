CREATE TABLE analysis_checks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    analyzer VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RAN', 'SKIPPED', 'FAILED')),
    reason TEXT,
    duration_ms BIGINT,
    files_analyzed INT NOT NULL DEFAULT 0,
    finding_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analysis_checks_review_analyzer UNIQUE (review_id, analyzer)
);

CREATE INDEX idx_analysis_checks_review ON analysis_checks(review_id);
