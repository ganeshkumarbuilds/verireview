-- V23: Generate wizard answers and explicit stack details.
--
-- The wizard asks the user every choice that the backend would otherwise
-- have defaulted (database, auth, deployment, integrations, generation
-- engine). Non-secret answers are stored as JSON so the plan and the agent
-- invocations can be explained from what the user actually chose.
--
-- Secrets (AI API key, database password) are NEVER written here. The
-- service filters secret question ids before serialising, and the existing
-- in-memory GenerationSecrets path remains the only place those values live.
ALTER TABLE generations ADD COLUMN IF NOT EXISTS language_runtime VARCHAR(200);
ALTER TABLE generations ADD COLUMN IF NOT EXISTS backend_custom VARCHAR(200);
ALTER TABLE generations ADD COLUMN IF NOT EXISTS frontend_custom VARCHAR(200);
ALTER TABLE generations ADD COLUMN IF NOT EXISTS answers_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE generations ADD COLUMN IF NOT EXISTS database_configure_later BOOLEAN NOT NULL DEFAULT FALSE;
