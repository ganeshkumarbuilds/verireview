-- Add the Rating Agent to the persisted agent-execution types.
ALTER TABLE agent_executions DROP CONSTRAINT IF EXISTS ck_agent_executions_type;
ALTER TABLE agent_executions ADD CONSTRAINT ck_agent_executions_type
  CHECK (agent_type IN ('PLANNING', 'REVIEW', 'CODING', 'VERIFIED', 'RATING'));
