package com.verireview.agent.dto;

import java.util.List;
import java.util.Map;

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
    String auth,
    java.util.List<String> integrations,
    String dbHost,
    Integer dbPort,
    String dbName,
    String dbUsername,
    String dbSslMode,
    String aiProvider,
    String apiKey,
    String baseUrl,
    String model,
    String languageRuntime,
    String backendCustom,
    String frontendCustom,
    Map<String, String> answers) {
}
