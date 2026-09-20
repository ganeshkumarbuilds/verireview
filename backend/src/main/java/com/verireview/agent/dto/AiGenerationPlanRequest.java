package com.verireview.agent.dto;

import java.util.List;

/**
 * Backend → AI generation-plan invocation (mirrors Python {@code PlanRequest}).
 * Serialized with snake_case keys. {@code apiKey} travels server-to-server
 * only and is never logged or persisted on either side.
 */
public record AiGenerationPlanRequest(
    String generationId,
    String requirement,
    String backend,
    String frontend,
    String database,
    String dbHost,
    Integer dbPort,
    String dbName,
    String dbUsername,
    String dbSslMode,
    String aiProvider,
    String apiKey,
    String baseUrl,
    String model) {
}
