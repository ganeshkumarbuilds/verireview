ALTER TABLE agent_executions
    ADD COLUMN IF NOT EXISTS current_activity VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS files_written INTEGER,
    ADD COLUMN IF NOT EXISTS files_planned INTEGER;
