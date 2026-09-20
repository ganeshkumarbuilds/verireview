package com.verireview.generation.dto;

import com.verireview.generation.GenerationAiProvider;
import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationDatabase;
import com.verireview.generation.GenerationFrontend;
import com.verireview.generation.GenerationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Generation state for polling. Secrets are NEVER exposed: the database
 * password is replaced by {@code databasePasswordConfigured} and the AI key
 * by {@code aiKeyConfigured}.
 */
public record GenerationResponse(
    UUID id,
    String name,
    String requirement,
    String description,
    GenerationBackend backend,
    GenerationFrontend frontend,
    GenerationDatabase database,
    DatabaseView databaseConfig,
    AiView aiConfig,
    GenerationStatus status,
    String error,
    UUID projectId,
    int iteration,
    int maxIterations,
    int revisionNumber,
    long revisionCount,
    ArtifactView artifact,
    PlanView plan,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * Latest validated project plan for inspection. Structured sections only —
   * file contents live on disk, secrets never appear here.
   */
  public record PlanView(
      int iteration,
      int fileCount,
      String architecture,
      List<String> dependencies,
      List<String> directories,
      String apis,
      List<String> steps) {
  }

  /**
   * Verified-artifact facts. Server-side storage paths are never exposed;
   * the ZIP download of a later phase resolves the artifact internally.
   */
  public record ArtifactView(
      UUID id,
      int iteration,
      int fileCount,
      long totalChars,
      String sha256,
      Instant createdAt) {
  }

  public record DatabaseView(
      String host,
      Integer port,
      String name,
      String username,
      String sslMode,
      boolean passwordConfigured) {
  }

  public record AiView(
      GenerationAiProvider provider,
      String model,
      String baseUrl,
      boolean keyConfigured) {
  }
}
