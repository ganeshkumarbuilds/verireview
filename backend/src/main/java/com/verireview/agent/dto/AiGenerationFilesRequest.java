package com.verireview.agent.dto;

import java.util.List;

/**
 * Backend → AI file-generation invocation (mirrors Python {@code FilesRequest}).
 * Only non-secret database metadata is included; the database password and
 * any other secret are never sent — generated code must use environment
 * variable placeholders instead.
 */
public record AiGenerationFilesRequest(
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
    String model,
    List<PlannedFileRef> plan) {

  public record PlannedFileRef(String path, String purpose) {
  }
}
