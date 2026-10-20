-- V20: honest sandbox-skip states and revertible patch snapshots.
--
-- 1) execution_runs gains a SKIPPED state so a run that never actually
--    executed (Docker sandbox disabled) is never persisted as a green
--    SUCCESS. The UI can then say "Not verified: sandbox unavailable".
-- 2) patches gains a REVERTED state so an applied patch that was rolled
--    back is distinguishable from a rejected proposal.
-- 3) patch_snapshots stores the exact pre-apply content of every file a
--    patch touches, so a Revert restores the previous file content.

ALTER TABLE execution_runs DROP CONSTRAINT IF EXISTS ck_execution_runs_status;
ALTER TABLE execution_runs ADD CONSTRAINT ck_execution_runs_status
  CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT', 'SKIPPED'));

ALTER TABLE execution_runs DROP CONSTRAINT IF EXISTS ck_execution_runs_build_status;
ALTER TABLE execution_runs ADD CONSTRAINT ck_execution_runs_build_status
  CHECK (build_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'TIMEOUT', 'SKIPPED'));

ALTER TABLE patches DROP CONSTRAINT IF EXISTS ck_patches_status;
ALTER TABLE patches ADD CONSTRAINT ck_patches_status
  CHECK (status IN ('PROPOSED', 'APPLIED', 'REJECTED', 'REVERTED'));

CREATE TABLE patch_snapshots (
    id               UUID PRIMARY KEY,
    patch_id         UUID NOT NULL REFERENCES patches (id) ON DELETE CASCADE,
    file_path        TEXT NOT NULL,
    existed          BOOLEAN NOT NULL DEFAULT TRUE,
    previous_content TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_patch_snapshots_patch_file ON patch_snapshots (patch_id, file_path);
CREATE INDEX ix_patch_snapshots_patch ON patch_snapshots (patch_id);
