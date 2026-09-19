package com.verireview.agent;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {
}
