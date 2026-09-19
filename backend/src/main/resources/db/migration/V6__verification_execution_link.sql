-- V6: Verification engine (Phase 11) — link verification to execution evidence
-- Backend-enforced verdict: VERIFIED only when build+tests pass and no new CRITICAL/HIGH

ALTER TABLE verification_runs ADD COLUMN IF NOT EXISTS execution_run_id UUID REFERENCES execution_runs (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_verification_runs_execution ON verification_runs (execution_run_id);
-- Ensure execution_runs verdict consistency: already has verdict check, no change needed
