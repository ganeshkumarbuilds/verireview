package com.verireview.generation;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionStatus;
import com.verireview.agent.AgentType;
import com.verireview.agent.AiServiceException;
import com.verireview.agent.AiServiceProperties;
import com.verireview.agent.GenerationAiClient;
import com.verireview.agent.ReviewAiClient;
import com.verireview.agent.VerifiedAgentClient;
import com.verireview.agent.dto.AiGenerationFilesRequest;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanRequest;
import com.verireview.agent.dto.AiGenerationPlanResult;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.audit.AuditService;
import com.verireview.execution.SandboxRunner;
import com.verireview.fix.Patch;
import com.verireview.fix.PatchStatus;
import com.verireview.fix.UnifiedDiffValidator;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.user.UserRepository;
import com.verireview.generation.GenerationExecutionEvidence;
import com.verireview.verification.VerificationVerdict;
import com.verireview.review.ReviewStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Async generation worker. Runs outside any database transaction (the AI run
 * takes minutes); it crosses into short {@link GenerationService}
 * transactions only to flip state and persist the finished project.
 *
 * <p>Never executes generated code and never writes to the primary database
 * from Python — the AI service only returns file contents; this worker
 * validates paths, scans for embedded secrets, and materializes the normal
 * {@link Project} + {@link ProjectFile} rows the Review workflow consumes.
 * Generated code is never run here (no sandbox execution in this phase).
 */
@Component
public class GenerationRunner {

  private static final Logger log = LoggerFactory.getLogger(GenerationRunner.class);

  /**
   * Prompt contract version, mirroring {@code PROMPT_VERSION} in the AI
   * service. Recorded on every execution row for traceability.
   */
  static final String PROMPT_VERSION = "generation/v1";

  static final int MAX_FILES = 60;
  static final int MAX_CHARS_PER_FILE = 100_000;
  static final long MAX_TOTAL_CHARS = 2_000_000L;

  private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
      Map.entry("java", "java"),
      Map.entry("py", "python"),
      Map.entry("js", "javascript"),
      Map.entry("jsx", "javascript"),
      Map.entry("ts", "typescript"),
      Map.entry("tsx", "typescript"),
      Map.entry("sql", "sql"),
      Map.entry("xml", "xml"),
      Map.entry("yml", "yaml"),
      Map.entry("yaml", "yaml"),
      Map.entry("json", "json"),
      Map.entry("md", "markdown"),
      Map.entry("html", "html"),
      Map.entry("css", "css"));

  private final GenerationService generations;
  private final GenerationAiClient aiClient;
  private final SandboxRunner sandboxRunner;
  private final VerifiedAgentClient verifiedAgentClient;
  private final ReviewAiClient reviewAiClient;
  private final AiServiceProperties aiServiceProperties;
  private final UserRepository users;
  private final ProjectRepository projects;
  private final ProjectFileRepository files;
  private final ProjectStorage storage;
  private final AuditService audits;

  public GenerationRunner(
      @Lazy GenerationService generations,
      GenerationAiClient aiClient,
      SandboxRunner sandboxRunner,
      VerifiedAgentClient verifiedAgentClient,
      ReviewAiClient reviewAiClient,
      AiServiceProperties aiServiceProperties,
      UserRepository users,
      ProjectRepository projects,
      ProjectFileRepository files,
      ProjectStorage storage,
      AuditService audits) {
    this.generations = generations;
    this.aiClient = aiClient;
    this.sandboxRunner = sandboxRunner;
    this.verifiedAgentClient = verifiedAgentClient;
    this.reviewAiClient = reviewAiClient;
    this.aiServiceProperties = aiServiceProperties;
    this.users = users;
    this.projects = projects;
    this.files = files;
    this.storage = storage;
    this.audits = audits;
  }

  /**
   * Generation pipeline: snapshot → iteration budget → PLANNING → CODING →
   * BUILDING → VERIFYING → VERIFIED → REVIEWING → REVIEWED.
   * After an applied generation patch, the rebuild path is:
   * REVIEWED → REBUILDING → REVERIFYING → VERIFIED/FAILED.
   * Files land in an isolated generation workspace; builds run in the Docker sandbox;
   * Verified Agent evaluates evidence; Review Agent reviews generated projects.
   * Re-review after a successful patch re-verification belongs to the next phase.
   */
  @Async("generationExecutor")
  public void runAsync(UUID generationId) {
    GenerationService.GenerationSnapshot snapshot = generations.snapshot(generationId);
    if (snapshot == null) {
      return;
    }
    // Secrets live in memory for the entire generation lifecycle (including fix loops).
    // They are cleared only when the generation reaches a terminal state.
    GenerationSecrets secrets = generations.peekSecrets(generationId);
    if (secrets == null || secrets.aiApiKey() == null) {
      fail(generationId, snapshot.ownerId(),
          "Generation credentials are no longer available. Please create a new generation.");
      return;
    }
    try {
      int iteration = generations.nextIteration(generationId);
      generations.markStatus(generationId, GenerationStatus.PLANNING, null);
      List<AiGenerationPlanResult.PlannedFile> plan =
          runPlanning(generationId, snapshot, secrets, iteration);

      generations.markStatus(generationId, GenerationStatus.CODING, null);
      runCoding(generationId, snapshot, secrets, iteration, plan);

      // Phase C: BUILDING → VERIFYING (or FAILED on build failure)
      generations.markStatus(generationId, GenerationStatus.BUILDING, null);
      UUID evidenceId = runBuilding(generationId, snapshot, iteration);
      GenerationExecutionEvidence evidence = generations.executionEvidence()
          .findById(evidenceId).orElseThrow();

      if (evidence.getBuildStatus() != GenerationExecutionEvidence.BuildStatus.SUCCESS) {
        fail(generationId, snapshot.ownerId(),
            "Build failed: " + evidence.getFailureReason());
        return;
      }

      // Build succeeded — run Verified Agent to evaluate evidence
      generations.markStatus(generationId, GenerationStatus.VERIFYING, null);
      UUID verificationRunId = generations.createVerificationRun(generationId, iteration, evidenceId);
      runVerifying(generationId, snapshot, iteration, evidence, verificationRunId);

      // VERIFIED → REVIEWING → REVIEWED
      Generation review = generations.findById(generationId);
      if (review.getStatus() == GenerationStatus.VERIFIED) {
        generations.markStatus(generationId, GenerationStatus.REVIEWING, null);
        runReviewing(generationId, snapshot, iteration);
      }

    } catch (Exception e) {
      // Never include request bodies or secrets in state: messages only.
      log.warn("Generation {} failed: {}", generationId, e.toString());
      fail(generationId, snapshot.ownerId(), truncate(messageOf(e), 2000));
    }
  }

  /**
   * Triggers the rebuild and re-verification flow for a generation after a patch
   * has been applied. This is called externally (e.g., from a controller) when
   * the user wants to rebuild after applying a patch.
   *
   * @param generationId the generation to rebuild
   * @param patchId the patch that was applied and triggered this rebuild
   */
  @Async("generationExecutor")
  public void triggerRebuildAndReverify(UUID generationId, UUID patchId) {
    GenerationService.GenerationSnapshot snapshot = generations.snapshot(generationId);
    if (snapshot == null) {
      return;
    }
    try {
      // Patch ownership/state is validated before dispatch. Keep the runner
      // responsible for the asynchronous rebuild/re-verification lifecycle.
      Patch patch = generations.getPatch(patchId);
      if (patch.getGeneration() == null || !patch.getGeneration().getId().equals(generationId)) {
        fail(generationId, snapshot.ownerId(), "Patch not found for this generation");
        return;
      }
      if (patch.getStatus() != PatchStatus.APPLIED) {
        fail(generationId, snapshot.ownerId(), "Patch must be in APPLIED state to trigger rebuild");
        return;
      }

      Generation gen = generations.findById(generationId);
      int iteration = gen.getIteration();

      // REVIEWED -> REBUILDING is the authoritative beginning of the
      // patch-triggered rebuild lifecycle.
      generations.markStatus(generationId, GenerationStatus.REBUILDING, null);
      runRebuildAndReverify(generationId, snapshot, iteration);

    } catch (Exception e) {
      log.warn("Generation {} rebuild failed: {}", generationId, e.toString());
      fail(generationId, snapshot.ownerId(), truncate(messageOf(e), 2000));
    }
  }

  private List<AiGenerationPlanResult.PlannedFile> runPlanning(
      UUID generationId,
      GenerationService.GenerationSnapshot snapshot,
      GenerationSecrets secrets,
      int iteration) {
    String hash = GenerationService.inputHash(
        snapshot.requirement(), snapshot.backend().name(), snapshot.frontend().name(),
        snapshot.database().name(), snapshot.aiModel(), PROMPT_VERSION, String.valueOf(iteration));
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.PLANNING, snapshot.aiModel(), PROMPT_VERSION, hash);
    long startedNanos = System.nanoTime();
    try {
      AiGenerationPlanResult result = planFiles(snapshot, secrets);
      validatePlan(result);
      UUID planId = generations.savePlan(generationId, iteration, result);
      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos), "plan:" + planId);
      return result.files();
    } catch (AiServiceException e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Planning failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      throw new GenerationException("Planning failed: " + e.getMessage(), e);
    } catch (GenerationException e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate(messageOf(e), 2000), millisSince(startedNanos), null);
      throw e;
    }
  }

  private void runCoding(
      UUID generationId,
      GenerationService.GenerationSnapshot snapshot,
      GenerationSecrets secrets,
      int iteration,
      List<AiGenerationPlanResult.PlannedFile> plan) {
    String hash = GenerationService.inputHash(
        snapshot.requirement(), snapshot.backend().name(), snapshot.frontend().name(),
        snapshot.database().name(), snapshot.aiModel(), PROMPT_VERSION,
        String.valueOf(iteration), String.valueOf(plan.size()));
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.CODING, snapshot.aiModel(), PROMPT_VERSION, hash);
    long startedNanos = System.nanoTime();
    try {
      List<AiGenerationFilesResult.GeneratedFile> generated = generateFiles(snapshot, secrets, plan);
      List<ValidatedFile> validated = review(generated, secrets);
      Path workspace = writeWorkspace(generationId, iteration, validated);
      long totalChars = validated.stream().mapToLong(file -> file.content().length()).sum();
      UUID artifactId = generations.recordArtifact(generationId, iteration,
          validated.size(), totalChars, manifestSha(validated), workspace.toString());
      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos), "artifact:" + artifactId);
    } catch (AiServiceException e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Code generation failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      throw new GenerationException("Code generation failed: " + e.getMessage(), e);
    } catch (GenerationException e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate(messageOf(e), 2000), millisSince(startedNanos), null);
      throw e;
    }
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }

  /** Backend-side plan validation: shape, size, paths and directories. */
  private void validatePlan(AiGenerationPlanResult result) {
    if (result.files().size() > MAX_FILES) {
      throw new GenerationException(
          "Plan proposes " + result.files().size() + " files, limit is " + MAX_FILES);
    }
    for (AiGenerationPlanResult.PlannedFile file : result.files()) {
      checkPlanPath(file.path(), "Planned file has an illegal path: " + file.path());
    }
    for (String directory : result.sections().directories()) {
      String normalized = directory.strip().replaceAll("/+$", "");
      checkPlanPath(normalized, "Planned directory is not project-relative: " + directory);
    }
  }

  private static void checkPlanPath(String path, String message) {
    try {
      UnifiedDiffValidator.checkProjectPath(path);
    } catch (UnifiedDiffValidator.DiffValidationException e) {
      throw new GenerationException(message);
    }
  }

  private AiGenerationPlanResult planFiles(
      GenerationService.GenerationSnapshot snapshot, GenerationSecrets secrets) {
    AiGenerationPlanRequest request = new AiGenerationPlanRequest(
        snapshot.id().toString(),
        snapshot.requirement(),
        snapshot.backend().name(),
        snapshot.frontend().name(),
        snapshot.database().name(),
        snapshot.dbHost(),
        snapshot.dbPort(),
        snapshot.dbName(),
        snapshot.dbUsername(),
        snapshot.dbSslMode(),
        snapshot.aiProvider().name(),
        secrets.aiApiKey(),
        snapshot.aiBaseUrl(),
        snapshot.aiModel());
    AiGenerationPlanResult result = aiClient.plan(request);
    if (result.files().size() > MAX_FILES) {
      throw new GenerationException(
          "Plan proposes " + result.files().size() + " files, limit is " + MAX_FILES);
    }
    return result;
  }

  private List<AiGenerationFilesResult.GeneratedFile> generateFiles(
      GenerationService.GenerationSnapshot snapshot,
      GenerationSecrets secrets,
      List<AiGenerationPlanResult.PlannedFile> plan) {
    List<AiGenerationFilesRequest.PlannedFileRef> refs = new ArrayList<>();
    for (AiGenerationPlanResult.PlannedFile item : plan) {
      refs.add(new AiGenerationFilesRequest.PlannedFileRef(item.path(), item.purpose()));
    }
    AiGenerationFilesRequest request = new AiGenerationFilesRequest(
        snapshot.id().toString(),
        snapshot.requirement(),
        snapshot.backend().name(),
        snapshot.frontend().name(),
        snapshot.database().name(),
        snapshot.dbHost(),
        snapshot.dbPort(),
        snapshot.dbName(),
        snapshot.dbUsername(),
        snapshot.dbSslMode(),
        snapshot.aiProvider().name(),
        secrets.aiApiKey(),
        snapshot.aiBaseUrl(),
        snapshot.aiModel(),
        refs);
    return aiClient.files(request).files();
  }

  private List<ValidatedFile> review(
      List<AiGenerationFilesResult.GeneratedFile> generated, GenerationSecrets secrets) {
    if (generated.size() > MAX_FILES) {
      throw new GenerationException(
          "Generation returned " + generated.size() + " files, limit is " + MAX_FILES);
    }
    long total = 0;
    List<ValidatedFile> validated = new ArrayList<>();
    for (AiGenerationFilesResult.GeneratedFile file : generated) {
      String path = file.path() == null ? "" : file.path().trim();
      String content = file.content() == null ? "" : file.content();
      try {
        UnifiedDiffValidator.checkProjectPath(path);
      } catch (UnifiedDiffValidator.DiffValidationException e) {
        throw new GenerationException("Generated file has an illegal path: " + path);
      }
      if (content.isEmpty()) {
        throw new GenerationException("Generated file is empty: " + path);
      }
      if (content.length() > MAX_CHARS_PER_FILE) {
        throw new GenerationException("Generated file is too large: " + path);
      }
      total += content.length();
      if (total > MAX_TOTAL_CHARS) {
        throw new GenerationException("Generated project exceeds the size limit");
      }
      if (content.indexOf('\0') >= 0) {
        throw new GenerationException("Generated file is not text: " + path);
      }
      assertNoEmbeddedSecret(content, path, secrets.dbPassword());
      assertNoEmbeddedSecret(content, path, secrets.aiApiKey());
      validated.add(new ValidatedFile(path, content, languageOf(path, file.language())));
    }
    if (validated.isEmpty()) {
      throw new GenerationException("Generation returned no usable files");
    }
    return validated;
  }

  private void assertNoEmbeddedSecret(String content, String path, String secret) {
    if (secret != null && content.contains(secret)) {
      throw new GenerationException(
          "Generated file embeds credentials (" + path + "); secrets must use environment variables");
    }
  }

  /**
   * Writes validated files into the isolated per-iteration generation
   * workspace. Never inside a project directory and never executed — later
   * phases build, verify and materialize from here.
   */
  private Path writeWorkspace(
      UUID generationId, int iteration, List<ValidatedFile> validated) {
    Path workspace = storage.generationWorkspaceDir(generationId, iteration);
    try {
      Files.createDirectories(workspace);
      for (ValidatedFile file : validated) {
        Path target = storage.resolveJailed(workspace, file.path());
        Files.createDirectories(target.getParent());
        Files.write(target, file.content().getBytes(StandardCharsets.UTF_8));
      }
      return workspace.toAbsolutePath().normalize();
    } catch (Exception e) {
      storage.deleteQuietly(workspace);
      throw new GenerationException("Could not store the generation workspace", e);
    }
  }

  /** Stable manifest hash over sorted path + content pairs for one iteration. */
  private static String manifestSha(List<ValidatedFile> validated) {
    List<ValidatedFile> sorted = new ArrayList<>(validated);
    sorted.sort(Comparator.comparing(ValidatedFile::path));
    StringBuilder manifest = new StringBuilder();
    for (ValidatedFile file : sorted) {
      manifest.append(file.path()).append('\n').append(file.content()).append('\n');
    }
    return sha256Hex(manifest.toString().getBytes(StandardCharsets.UTF_8));
  }

  private UUID materialize(
      GenerationService.GenerationSnapshot snapshot, List<ValidatedFile> validated) {
    if (projects.existsByOwnerIdAndNameAndDeletedAtIsNull(snapshot.ownerId(), snapshot.name())) {
      throw new GenerationException("A project with this name already exists");
    }
    Project project = new Project(
        users.getReferenceById(snapshot.ownerId()), snapshot.name(), ProjectSourceType.GENERATED);
    project.setDescription(snapshot.description());
    project.setLanguage(primaryLanguage(snapshot.backend()));
    try {
      projects.saveAndFlush(project);
    } catch (DataIntegrityViolationException e) {
      throw new GenerationException("A project with this name already exists");
    }
    Path projectDir = storage.projectDir(project.getId());
    try {
      Files.createDirectories(projectDir);
      List<ProjectFile> rows = new ArrayList<>();
      for (ValidatedFile file : validated) {
        Path target = storage.resolveJailed(projectDir, file.path());
        Files.createDirectories(target.getParent());
        byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes);
        ProjectFile row = new ProjectFile(project, file.path(), bytes.length, sha256Hex(bytes));
        row.setLanguage(file.language());
        row.setContentRef(file.path());
        rows.add(row);
      }
      files.saveAll(rows);
      project.setStorageRef(projectDir.toAbsolutePath().normalize().toString());
      projects.save(project);
    } catch (GenerationException e) {
      storage.deleteQuietly(projectDir);
      throw e;
    } catch (Exception e) {
      storage.deleteQuietly(projectDir);
      throw new GenerationException("Could not store the generated project", e);
    }
    return project.getId();
  }

  private void fail(UUID generationId, UUID ownerId, String error) {
    try {
      generations.markStatus(generationId, GenerationStatus.FAILED, error);
    } catch (Exception e) {
      log.warn("Generation {} could not record failure: {}", generationId, e.toString());
    }
    audits.record(null, "GENERATION_FAILED", "generation", generationId.toString());
  }

  /**
   * Runs the build+test in the Docker sandbox for the generation workspace.
   * Persists execution evidence with build/test status.
   *
   * @return the persisted execution evidence id
   */
  private UUID runBuilding(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration) {
    String command = buildCommandForStack(snapshot.backend(), snapshot.frontend(), snapshot.database());
    Path workspace = storage.generationWorkspaceDir(generationId, iteration);
    SandboxRunner.ExecutionResult result;
    try {
      result = sandboxRunner.executeBuild(workspace, command);
    } catch (SandboxRunner.SandboxException e) {
      return generations.recordExecutionEvidence(generationId, iteration, command,
          1, 0, "", e.getMessage(),
          GenerationExecutionEvidence.BuildStatus.FAILURE,
          GenerationExecutionEvidence.TestStatus.NOT_APPLICABLE,
          "Sandbox error: " + e.getMessage());
    }

    GenerationExecutionEvidence.BuildStatus buildStatus;
    GenerationExecutionEvidence.TestStatus testStatus;
    String failureReason = null;

    if (result.timedOut()) {
      buildStatus = GenerationExecutionEvidence.BuildStatus.TIMEOUT;
      testStatus = GenerationExecutionEvidence.TestStatus.TIMEOUT;
      failureReason = "Build timed out after " + sandboxRunner.getExecutionTimeoutSeconds() + "s";
    } else if (result.exitCode() == 0) {
      buildStatus = GenerationExecutionEvidence.BuildStatus.SUCCESS;
      testStatus = GenerationExecutionEvidence.TestStatus.SUCCESS;
    } else {
      // Check if it's a build failure or test failure by examining output
      String combined = (result.stdout() == null ? "" : result.stdout()) +
          (result.stderr() == null ? "" : result.stderr());
      if (combined.contains("BUILD FAILURE") || combined.contains("FAILURE") ||
          combined.contains("error:") || combined.contains("Error:")) {
        buildStatus = GenerationExecutionEvidence.BuildStatus.FAILURE;
        testStatus = GenerationExecutionEvidence.TestStatus.NOT_APPLICABLE;
        failureReason = "Build failed (exit code " + result.exitCode() + ")";
      } else {
        buildStatus = GenerationExecutionEvidence.BuildStatus.SUCCESS;
        testStatus = GenerationExecutionEvidence.TestStatus.FAILURE;
        failureReason = "Tests failed (exit code " + result.exitCode() + ")";
      }
    }

    String stdout = truncateForStorage(result.stdout());
    String stderr = truncateForStorage(result.stderr());

    return generations.recordExecutionEvidence(generationId, iteration, command,
        result.exitCode(), result.durationMs(), stdout, stderr,
        buildStatus, testStatus, failureReason);
  }

  /**
   * Runs the Verified Agent to evaluate build evidence and produce a verdict.
   * The Verified Agent is READ-ONLY — it inspects evidence but never modifies files.
   */
  private void runVerifying(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration,
      GenerationExecutionEvidence evidence, UUID verificationRunId) {
    // Start VERIFIED agent execution
    String hash = GenerationService.inputHash(
        snapshot.id().toString(), String.valueOf(iteration), evidence.getCommand(),
        String.valueOf(evidence.getExitCode()), String.valueOf(evidence.getBuildStatus()));
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.VERIFIED, snapshot.aiModel(), PROMPT_VERSION, hash);
    long startedNanos = System.nanoTime();
    try {
      // Invoke Verified Agent via AI service
      VerifiedAgentClient.VerifiedAgentResult verified = verifiedAgentClient.evaluate(
          generationId, iteration, evidence, snapshot);

      // Update verification run with verdict
      generations.updateVerificationRun(verificationRunId,
          verified.testsTotal(), verified.testsPassed(), verified.testsFailed(), verified.testsSkipped(),
          verified.verdictEnum(), verified.logRef(), millisSince(startedNanos));

      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos), "verification:" + verificationRunId);

      // Check verdict and transition state
      if (verified.verdictEnum() == VerificationVerdict.VERIFIED) {
        generations.markStatus(generationId, GenerationStatus.VERIFIED, null);
      } else {
        fail(generationId, snapshot.ownerId(),
            "Verification failed: " + verified.reason());
      }
    } catch (Exception e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Verification failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      fail(generationId, snapshot.ownerId(), "Verification error: " + e.getMessage());
    }
  }

  /**
   * Runs the Review Agent to evaluate the generated project files.
   * The Review Agent is READ-ONLY — it inspects files but never modifies them.
   */
  private void runReviewing(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration) {
    // Start REVIEW agent execution
    String hash = GenerationService.inputHash(
        snapshot.id().toString(), String.valueOf(iteration), "review");
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.REVIEW, snapshot.aiModel(), PROMPT_VERSION, hash);
    long startedNanos = System.nanoTime();
    try {
      // Create a review record for this generation
      UUID reviewId = generations.createReview(generationId, iteration);

      // Link the review to the agent execution
      generations.linkReviewToAgentExecution(executionId, reviewId);

      // Read files from the generation workspace
      Path workspace = storage.generationWorkspaceDir(generationId, iteration);
      List<com.verireview.agent.dto.AiFileSnapshot> files = readWorkspaceFiles(workspace);

      // Prepare deterministic findings (empty for generation review - no deterministic tools run yet)
      List<com.verireview.agent.dto.AiDeterministicFinding> deterministic = new ArrayList<>();

      // Build review request
      com.verireview.agent.dto.AiReviewRequest request = new com.verireview.agent.dto.AiReviewRequest(
          reviewId.toString(),
          generationId.toString(),
          primaryLanguage(snapshot.backend()),
          files,
          deterministic,
          "gen-review-" + reviewId);

      // Invoke Review Agent via AI service
      com.verireview.agent.dto.AiReviewResult result = reviewAiClient.review(request);

      // Persist AI findings using shared validation/dedup logic
      int added = generations.persistGenerationReviewFindings(
          reviewId, result, result.promptVersion(), aiServiceProperties.modelLabel());

      // Update review with results
      generations.updateReview(reviewId, ReviewStatus.COMPLETED, added, null);

      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos), "review:" + reviewId + " ai:" + added);

      // Transition to REVIEWED
      generations.markStatus(generationId, GenerationStatus.REVIEWED, null);

    } catch (Exception e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Review failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      fail(generationId, snapshot.ownerId(), "Review error: " + e.getMessage());
    }
  }

  /**
   * Runs the rebuild and re-verification flow for a generation after a patch
   * has been applied. Uses the current iteration and the modified generation
   * workspace.
   *
   * @param generationId the generation to rebuild
   * @param snapshot the generation snapshot
   * @param patchId the patch that was applied and triggered this rebuild
   */
  private void runRebuildAndReverify(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration) {
    // The caller has already transitioned REVIEWED -> REBUILDING.
    UUID evidenceId = runBuilding(generationId, snapshot, iteration);
    GenerationExecutionEvidence evidence = generations.executionEvidence()
        .findById(evidenceId).orElseThrow();

    if (evidence.getBuildStatus() != GenerationExecutionEvidence.BuildStatus.SUCCESS) {
      fail(generationId, snapshot.ownerId(),
          "Rebuild failed: " + evidence.getFailureReason());
      return;
    }

    // REBUILDING -> REVERIFYING before invoking the read-only Verified Agent.
    generations.markStatus(generationId, GenerationStatus.REVERIFYING, null);

    /*
     * verification_runs currently enforces exactly one owner:
     * generation_id OR patch_id. A generation rebuild verification therefore
     * remains generation-owned. The applied patch is preserved independently
     * by the Patch/FixRequest records and is not written into both ownership
     * columns.
     */
    UUID verificationRunId = generations.createVerificationRun(
        generationId, iteration, evidenceId);

    runVerifying(generationId, snapshot, iteration, evidence, verificationRunId);

    // Successful verification transitions to VERIFIED inside runVerifying().
    // The Review Agent is intentionally not invoked here; re-review is Task 8.
  }

  /**
   * Reads files from the generation workspace for review.
   */
  private List<com.verireview.agent.dto.AiFileSnapshot> readWorkspaceFiles(Path workspace) {
    List<com.verireview.agent.dto.AiFileSnapshot> snapshots = new ArrayList<>();
    try {
      if (Files.exists(workspace)) {
        Files.walk(workspace)
            .filter(Files::isRegularFile)
            .forEach(file -> {
              try {
                String relativePath = workspace.relativize(file).toString();
                String content = Files.readString(file);
                if (content.indexOf('\0') < 0 && !content.isBlank()) {
                  snapshots.add(new com.verireview.agent.dto.AiFileSnapshot(
                      relativePath,
                      languageOf(relativePath, null),
                      content,
                      false));
                }
              } catch (Exception ignored) {
                // Skip unreadable files
              }
            });
      }
    } catch (Exception ignored) {
      // Return empty list on error
    }
    return snapshots;
  }

  /**
   * Derives the build/test command from the selected stack.
   */
  private String buildCommandForStack(GenerationBackend backend, GenerationFrontend frontend,
      GenerationDatabase database) {
    // Backend commands
    if (backend == GenerationBackend.JAVA_SPRING_BOOT) {
      return "mvn -B test";
    } else if (backend == GenerationBackend.PYTHON_FASTAPI) {
      return "pytest -v";
    } else if (backend == GenerationBackend.NODEJS) {
      return "npm test";
    }
    // Frontend commands (if no backend)
    if (frontend == GenerationFrontend.REACT_TYPESCRIPT) {
      return "npm test";
    }
    // Python-only projects
    if (database != GenerationDatabase.NONE && backend == null) {
      return "pytest -v";
    }
    return "echo 'no build command for stack' && exit 1";
  }

  /**
   * Truncates output for storage (max 100KB each).
   */
  private String truncateForStorage(String content) {
    if (content == null) return null;
    int max = 100_000;
    if (content.length() <= max) return content;
    return content.substring(0, max) + "\n...[truncated at " + max + " bytes]";
  }

  static String primaryLanguage(GenerationBackend backend) {
    return switch (backend) {
      case JAVA_SPRING_BOOT -> "java";
      case PYTHON_FASTAPI -> "python";
      case NODEJS -> "javascript";
    };
  }

  static String languageOf(String path, String hint) {
    if (hint != null && !hint.isBlank()) {
      return hint.trim().toLowerCase(Locale.ROOT);
    }
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot == path.length() - 1) {
      return null;
    }
    return LANGUAGE_BY_EXTENSION.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static String messageOf(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : message;
  }

  static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  record ValidatedFile(String path, String content, String language) {
  }

  /** Internal failure with a user-safe message (no secrets, no bodies). */
  static class GenerationException extends RuntimeException {
    GenerationException(String message) {
      super(message);
    }

    GenerationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
