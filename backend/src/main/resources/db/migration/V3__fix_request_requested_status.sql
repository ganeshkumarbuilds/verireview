-- V3: Phase 9A fix-request foundation.
-- 1. Rename the initial status PENDING → REQUESTED (explicit approval language).
-- 2. Add the optional failure/error message column.
-- Additive metadata changes only; existing PENDING rows migrate forward.

UPDATE fix_requests SET status = 'REQUESTED' WHERE status = 'PENDING';

ALTER TABLE fix_requests ALTER COLUMN status SET DEFAULT 'REQUESTED';

ALTER TABLE fix_requests DROP CONSTRAINT ck_fix_requests_status;
ALTER TABLE fix_requests ADD CONSTRAINT ck_fix_requests_status CHECK (status IN (
    'REQUESTED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED'));

DROP INDEX IF EXISTS uq_fix_requests_one_open_per_finding;
CREATE UNIQUE INDEX uq_fix_requests_one_open_per_finding ON fix_requests (finding_id)
    WHERE status IN ('REQUESTED', 'IN_PROGRESS');

ALTER TABLE fix_requests ADD COLUMN error TEXT;
