package com.verireview.agent.dto;

import java.util.List;
import java.util.Map;

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
    Map<String, String> answers,
    List<PlannedFileRef> plan,
    Integer fixRound,
    List<FixFindingInstruction> fixFindings,
    List<ExistingFileSnapshot> existingFiles) {

  public record PlannedFileRef(String path, String purpose) {
  }

  public record FixFindingInstruction(
      String file, Integer line, String category, String severity, String title, String explanation) {
  }

  public record ExistingFileSnapshot(String path, String content) {
  }
}
