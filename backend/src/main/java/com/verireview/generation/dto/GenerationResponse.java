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
 * Backend-authoritative generation state for polling and the generation
 * control-center UI.
 *
 * <p>Secrets are NEVER exposed: database passwords and AI keys are represented
 * only by configured flags. Workflow statistics, agent execution state,
 * verification state, review state, and artifact readiness are derived by the
 * backend and are not client-controlled.
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
    WorkflowStatsView workflowStats,
    VerificationView verification,
    ReviewView review,
    List<AgentStepView> agentWorkflow,
    boolean downloadReady,
    boolean previewReady,
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

  /**
   * Meaningful issue/fix counts for the generation workspace.
   *
   * <p>The counts are produced by the backend from persisted findings and
   * their statuses. They must never be calculated solely by the frontend.
   */
  public record WorkflowStatsView(
      int totalFindings,
      int openFindings,
      int fixedFindings,
      int bugCount,
      int issueCount,
      int errorCount) {
  }

  /**
   * Latest backend verification result for the current generation iteration.
   */
  public record VerificationView(
      String verdict,
      String buildStatus,
      int testsTotal,
      int testsPassed,
      int testsFailed,
      int testsSkipped,
      Long durationMs,
      String logRef) {
  }

  /**
   * Latest review result for the current generation iteration.
   */
  public record ReviewView(
      String status,
      int findingCount,
      String error) {
  }

  /**
   * One persisted agent execution shown in the workflow timeline.
   *
   * <p>No prompt, model secret, API key, source contents, or internal storage
   * path is exposed here.
   */
  public record AgentStepView(
      String agentType,
      String status,
      Long durationMs,
      String error,
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
