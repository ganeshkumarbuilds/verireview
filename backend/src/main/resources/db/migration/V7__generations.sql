-- V7: Project generation jobs (Generate workflow)
-- Secrets are NEVER persisted: no password / api-key columns exist here
-- by design. Non-secret connection metadata and AI provider/model only.

CREATE TABLE IF NOT EXISTS generations (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name VARCHAR(200) NOT NULL,
  requirement TEXT NOT NULL,
  description TEXT,
  backend_stack VARCHAR(30) NOT NULL,
  frontend_stack VARCHAR(30) NOT NULL,
  database_type VARCHAR(30) NOT NULL,
  db_host VARCHAR(500),
  db_port INTEGER,
  db_name VARCHAR(200),
  db_username VARCHAR(200),
  db_ssl_mode VARCHAR(50),
  ai_provider VARCHAR(30) NOT NULL,
  ai_model VARCHAR(200) NOT NULL,
  ai_base_url VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  error TEXT,
  project_id UUID REFERENCES projects (id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_generations_owner_name UNIQUE (owner_id, name)
);
CREATE INDEX IF NOT EXISTS ix_generations_owner ON generations (owner_id);
CREATE INDEX IF NOT EXISTS ix_generations_project ON generations (project_id);
