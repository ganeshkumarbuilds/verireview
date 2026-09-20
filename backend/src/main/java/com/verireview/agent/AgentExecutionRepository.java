package com.verireview.agent;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {

  List<AgentExecution> findByGenerationIdOrderByCreatedAtAsc(UUID generationId);

  List<AgentExecution> findByGenerationIdAndAgentTypeOrderByCreatedAtAsc(
      UUID generationId, AgentType agentType);
}
