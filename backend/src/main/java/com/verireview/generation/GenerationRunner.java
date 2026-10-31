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
import com.verireview.generation.dto.GenerationResponse;
import com.verireview.verification.VerificationVerdict;
import com.verireview.review.Review;
import com.verireview.review.ReviewStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
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
  private final GenerationStaticAnalyzer staticAnalyzer;

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
      AuditService audits,
      GenerationStaticAnalyzer staticAnalyzer) {
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
    this.staticAnalyzer = staticAnalyzer;
  }

  /**
   * Generation pipeline: snapshot → iteration budget → PLANNING → CODING →
   * BUILDING → VERIFYING → VERIFIED → REVIEWING → REVIEWED.
   * After an applied generation patch, the rebuild path is:
   * REVIEWED → REBUILDING → REVERIFYING → VERIFIED → REVIEWING → REVIEWED
   * (or FAILED). Files land in an isolated generation workspace; builds run
   * in the Docker sandbox; Verified Agent evaluates evidence; Review Agent
   * reviews generated projects and re-reviews them after every fix.
   */
  @Async("generationExecutor")
  public void runAsync(UUID generationId) {
    GenerationService.GenerationSnapshot snapshot = generations.snapshot(generationId);
    if (snapshot == null) {
      return;
    }
    // Secrets live in memory for the entire generation lifecycle (including fix loops).
    // They are cleared only when the generation reaches a terminal state.
    // NONE-provider runs carry no key by design and skip this gate.
    GenerationSecrets secrets = generations.peekSecrets(generationId);
    boolean needsKey = snapshot.aiProvider() != GenerationAiProvider.NONE;
    if (secrets == null || (needsKey && secrets.aiApiKey() == null)) {
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

      if (evidence.getBuildStatus() == GenerationExecutionEvidence.BuildStatus.SKIPPED) {
        // The Docker sandbox is disabled: build/tests never ran. Record the
        // fact honestly (SKIPPED, "Not verified"), still run code review, and
        // keep the download gate locked because no verification is VERIFIED.
        generations.markStatus(generationId, GenerationStatus.VERIFYING, null);
        UUID skippedRunId = generations.createVerificationRun(
            generationId, iteration, evidenceId);
        runSkippedVerification(generationId, snapshot, iteration, evidence, skippedRunId);
        generations.markStatus(generationId, GenerationStatus.REVIEWING, null);
        runReviewing(generationId, snapshot, iteration);
        return;
      }

      if (evidence.getBuildStatus() != GenerationExecutionEvidence.BuildStatus.SUCCESS) {
        fail(generationId, snapshot.ownerId(),
            "Build failed: " + evidence.getFailureReason());
        return;
      }

      // Build succeeded — run Verified Agent to evaluate evidence
      generations.markStatus(generationId, GenerationStatus.VERIFYING, null);
      UUID verificationRunId = generations.createVerificationRun(generationId, iteration, evidenceId);
      if (snapshot.aiProvider() == GenerationAiProvider.NONE) {
        runDeterministicVerifying(generationId, snapshot, iteration, evidence, verificationRunId);
      } else {
        runVerifying(generationId, snapshot, iteration, evidence, verificationRunId);
      }

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
      if (gen.getStatus() != GenerationStatus.REBUILDING) {
        generations.markStatus(generationId, GenerationStatus.REBUILDING, null);
      }
      runRebuildAndReverify(generationId, snapshot, iteration);
    } catch (Exception e) {
      log.warn("Generation {} rebuild failed: {}", generationId, e.toString());
      fail(generationId, snapshot.ownerId(), truncate(messageOf(e), 2000));
    }
  }

    @Async("generationExecutor")
    public void runApprovedFix(UUID generationId, UUID approvalId) {
      GenerationService.GenerationSnapshot snapshot = generations.snapshot(generationId);
      if (snapshot == null) return;
      GenerationSecrets secrets = generations.peekSecrets(generationId);
      GenerationService.FixRunContext context = generations.fixRunContext(generationId, approvalId);
      int round = context.roundNumber();
      generations.beginFixApproval(approvalId);
      try {
        if (secrets == null || secrets.aiApiKey() == null) {
          throw new GenerationException("AI provider key is missing. Enter it again and retry this round.");
        }

        Path beforeWorkspace = storage.generationWorkspaceDir(generationId, round - 1);
        Path workspace = storage.generationWorkspaceDir(generationId, round);
        copyWorkspace(beforeWorkspace, workspace);
        Map<String, String> currentFiles = readTextFileMap(workspace);
        Set<String> targets = GenerationFixScope.affectedFiles(context.findings(), currentFiles);
        if (targets.isEmpty()) {
          throw new GenerationException("No generated files matched the selected findings.");
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (String path : targets) {
          String content = currentFiles.get(path);
          if (content == null) {
            throw new GenerationException("A selected finding points to a file that no longer exists.");
          }
          before.put(path, content);
        }

        Map<String, String> purposes = new LinkedHashMap<>();
        AiGenerationPlanResult approvedPlan = generations.savedPlan(generationId, round - 1);
        if (approvedPlan != null) {
          approvedPlan.files().forEach(file -> purposes.put(file.path(), file.purpose()));
        }
        List<AiGenerationFilesRequest.PlannedFileRef> plan = targets.stream()
            .sorted()
            .map(path -> new AiGenerationFilesRequest.PlannedFileRef(
                path, purposes.getOrDefault(path, "User-approved fix for reviewed findings")))
            .toList();
        List<AiGenerationFilesRequest.FixFindingInstruction> instructions =
            context.findings().stream()
                .map(finding -> new AiGenerationFilesRequest.FixFindingInstruction(
                    finding.getFilePath(),
                    finding.getLineStart(),
                    finding.getCategory() == null ? "CODE_QUALITY" : finding.getCategory().name(),
                    finding.getSeverity() == null ? "MEDIUM" : finding.getSeverity().name(),
                    finding.getTitle(),
                    finding.getExplanation() == null ? finding.getDescription() : finding.getExplanation()))
                .toList();
        List<AiGenerationFilesRequest.ExistingFileSnapshot> snapshots = currentFiles.entrySet().stream()
            .map(entry -> new AiGenerationFilesRequest.ExistingFileSnapshot(entry.getKey(), entry.getValue()))
            .toList();

        UUID executionId = generations.startAgentExecution(
            generationId, AgentType.CODING, snapshot.aiModel(), PROMPT_VERSION,
            GenerationService.inputHash(generationId.toString(), String.valueOf(round),
                targets.toString(), instructions.toString()));
        long started = System.nanoTime();
        try {
          AiGenerationFilesRequest request = new AiGenerationFilesRequest(
              snapshot.id().toString(),
              snapshot.requirement(),
              snapshot.backend().name(),
              snapshot.frontend().name(),
              snapshot.database().name(),
              snapshot.auth().name(),
              snapshot.integrations().stream().map(Enum::name).toList(),
              snapshot.dbHost(),
              snapshot.dbPort(),
              snapshot.dbName(),
              snapshot.dbUsername(),
              snapshot.dbSslMode(),
              snapshot.aiProvider().name(),
              secrets.aiApiKey(),
              snapshot.aiBaseUrl(),
              snapshot.aiModel(),
              snapshot.languageRuntime(),
              snapshot.backendCustom(),
              snapshot.frontendCustom(),
              snapshot.answers(),
              plan,
              round,
              instructions,
              snapshots);
          List<AiGenerationFilesResult.GeneratedFile> generated = aiClient.files(request).files();
          if (!generated.stream().map(AiGenerationFilesResult.GeneratedFile::path)
              .collect(java.util.stream.Collectors.toSet()).equals(targets)
              || generated.size() != targets.size()) {
            throw new GenerationException("Coding output did not match the approved affected-file list.");
          }
          List<ValidatedFile> validated = review(generated, secrets);
          Map<String, String> after = new LinkedHashMap<>();
          validated.forEach(file -> after.put(file.path(), file.content()));
          for (ValidatedFile file : validated) {
            Path target = storage.resolveJailed(workspace, file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
          }
          generations.saveFixFileVersions(generationId, approvalId, round, before, after);
          long chars = readGeneratedFiles(workspace).stream()
              .mapToLong(file -> file.content().length()).sum();
          List<ValidatedFile> complete = review(readGeneratedFiles(workspace), secrets);
          UUID artifact = generations.recordArtifact(
              generationId, round, complete.size(), chars, manifestSha(complete), workspace.toString());
          generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
              null, millisSince(started), "artifact:" + artifact);
        } catch (Exception e) {
          generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
              truncate("Coding agent could not complete the approved fix: " + messageOf(e), 2000),
              millisSince(started), null);
          throw new GenerationException(messageOf(e), e);
        }

        generations.markStatus(generationId, GenerationStatus.REBUILDING, null);
        runRebuildAndReverify(generationId, snapshot, round);
      } catch (Exception e) {
        generations.setFixApprovalStatus(approvalId, "FAILED", null);
        log.warn("Generation {} approved fix round {} failed: {}", generationId, round, e.toString());
        fail(generationId, snapshot.ownerId(),
            "The approved fix round failed. Retry the failed stage or revert its files.");
      } finally {
        generations.clearSecrets(generationId);
      }
    }

    private void copyWorkspace(Path source, Path target) {
      if (!Files.isDirectory(source)) {
        throw new GenerationException("The previous generated files are unavailable for this fix round.");
      }
      try (var paths = Files.walk(source)) {
        for (Path entry : paths.toList()) {
          if (Files.isSymbolicLink(entry)) {
            throw new GenerationException("A generated workspace contains an unsafe symbolic link.");
          }
          Path destination = target.resolve(source.relativize(entry)).normalize();
          if (!destination.startsWith(target.normalize())) {
            throw new GenerationException("A generated file path escaped its workspace.");
          }
          if (Files.isDirectory(entry)) {
            Files.createDirectories(destination);
          } else if (Files.isRegularFile(entry)) {
            Files.createDirectories(destination.getParent());
            Files.copy(entry, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          }
        }
      } catch (Exception e) {
        storage.deleteQuietly(target);
        throw new GenerationException("Could not prepare the isolated fix workspace", e);
      }
    }

    private Map<String, String> readTextFileMap(Path workspace) {
      Map<String, String> files = new LinkedHashMap<>();
      try (var paths = Files.walk(workspace)) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          if (Files.isSymbolicLink(path) || Files.size(path) > MAX_CHARS_PER_FILE) continue;
          String relative = workspace.relativize(path).toString().replace('\\', '/');
          String content = Files.readString(path, StandardCharsets.UTF_8);
          if (content.indexOf('\0') < 0) files.put(relative, content);
        }
        return files;
      } catch (Exception e) {
        throw new GenerationException("Could not read generated files for the approved fix", e);
      }
    }


  @Async("generationExecutor")
  public void retryFailedStage(UUID generationId, AgentType agentType, UUID approvalId) {
    GenerationService.GenerationSnapshot snapshot = generations.snapshot(generationId);
    if (snapshot == null) return;
    GenerationSecrets secrets = generations.peekSecrets(generationId);
    int round = generations.findById(generationId).getIteration();
    try {
      switch (agentType) {
        case PLANNING -> runPlanning(generationId, snapshot, secrets, round);
        case CODING -> {
          if (approvalId == null) {
            AiGenerationPlanResult plan = generations.savedPlan(generationId, round);
            if (plan == null) {
              throw new GenerationException("The approved plan is unavailable for coding retry.");
            }
            runCoding(generationId, snapshot, secrets, round, plan.files());
          } else {
            retryApprovedCodingOnly(generationId, snapshot, secrets, approvalId);
          }
        }
        case VERIFIED -> {
          GenerationService.RetryVerificationContext context =
              generations.retryVerificationContext(generationId, round);
          runVerifying(generationId, snapshot, round, context.evidence(), context.verificationRunId());
        }
        case REVIEW -> {
          runReviewing(generationId, snapshot, round, false);
          generations.finishReviewRound(generationId, round);
        }
        case RATING -> {
          runRating(generationId, snapshot, round);
          if (generations.findById(generationId).getStatus() != GenerationStatus.FAILED) {
            generations.finishReviewRound(generationId, round);
          }
        }
      }
    } catch (Exception e) {
      log.warn("Generation {} stage {} retry failed: {}", generationId, agentType, e.toString());
      fail(generationId, snapshot.ownerId(),
          "The " + agentType.name().toLowerCase(Locale.ROOT)
              + " stage could not be retried. Review the error and retry again.");
    } finally {
      generations.clearSecrets(generationId);
    }
  }

  private void retryApprovedCodingOnly(
      UUID generationId,
      GenerationService.GenerationSnapshot snapshot,
      GenerationSecrets secrets,
      UUID approvalId) {
    GenerationService.FixRunContext context = generations.fixRunContext(generationId, approvalId);
    int round = context.roundNumber();
    if (secrets == null || secrets.aiApiKey() == null) {
      throw new GenerationException("Enter the AI provider key again to retry coding.");
    }
    Path workspace = storage.generationWorkspaceDir(generationId, round);
    storage.deleteQuietly(workspace);
    copyWorkspace(storage.generationWorkspaceDir(generationId, round - 1), workspace);
    Map<String, String> current = readTextFileMap(workspace);
    Set<String> targets = GenerationFixScope.affectedFiles(context.findings(), current);
    Map<String, String> before = new LinkedHashMap<>();
    targets.forEach(path -> before.put(path, current.get(path)));
    List<AiGenerationFilesRequest.FixFindingInstruction> instructions =
        context.findings().stream()
            .map(finding -> new AiGenerationFilesRequest.FixFindingInstruction(
                finding.getFilePath(), finding.getLineStart(),
                finding.getCategory() == null ? "CODE_QUALITY" : finding.getCategory().name(),
                finding.getSeverity() == null ? "MEDIUM" : finding.getSeverity().name(),
                finding.getTitle(),
                finding.getExplanation() == null ? finding.getDescription() : finding.getExplanation()))
            .toList();
    List<AiGenerationFilesRequest.PlannedFileRef> plan = targets.stream().sorted()
        .map(path -> new AiGenerationFilesRequest.PlannedFileRef(path, "Approved fix target"))
        .toList();
    List<AiGenerationFilesRequest.ExistingFileSnapshot> snapshots = current.entrySet().stream()
        .map(entry -> new AiGenerationFilesRequest.ExistingFileSnapshot(entry.getKey(), entry.getValue()))
        .toList();
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.CODING, snapshot.aiModel(), PROMPT_VERSION,
        GenerationService.inputHash(generationId.toString(), String.valueOf(round), targets.toString()));
    long started = System.nanoTime();
    try {
      List<AiGenerationFilesResult.GeneratedFile> generated = aiClient.files(new AiGenerationFilesRequest(
          snapshot.id().toString(), snapshot.requirement(), snapshot.backend().name(),
          snapshot.frontend().name(), snapshot.database().name(), snapshot.auth().name(),
          snapshot.integrations().stream().map(Enum::name).toList(), snapshot.dbHost(),
          snapshot.dbPort(), snapshot.dbName(), snapshot.dbUsername(), snapshot.dbSslMode(),
          snapshot.aiProvider().name(), secrets.aiApiKey(), snapshot.aiBaseUrl(), snapshot.aiModel(),
          snapshot.languageRuntime(), snapshot.backendCustom(), snapshot.frontendCustom(),
          snapshot.answers(), plan, round, instructions, snapshots)).files();
      if (generated.size() != targets.size()
          || !generated.stream().map(AiGenerationFilesResult.GeneratedFile::path)
              .collect(java.util.stream.Collectors.toSet()).equals(targets)) {
        throw new GenerationException("Coding output did not match the approved affected files.");
      }
      List<ValidatedFile> validated = review(generated, secrets);
      Map<String, String> after = new LinkedHashMap<>();
      for (ValidatedFile file : validated) {
        after.put(file.path(), file.content());
        Path target = storage.resolveJailed(workspace, file.path());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
      }
      generations.saveFixFileVersions(generationId, approvalId, round, before, after);
      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(started), "fix-round:" + round);
      generations.setFixApprovalStatus(approvalId, "APPROVED", null);
    } catch (Exception e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          "Coding retry failed: " + messageOf(e), millisSince(started), null);
      throw new GenerationException("Coding retry failed: " + messageOf(e), e);
    }
  }

  @Async("generationExecutor")
  public void rereviewAfterRevert(UUID generationId, int round) {
    GenerationService.GenerationSnapshot snapshot = generations.snapshot(generationId);
    if (snapshot == null) return;
    try {
      runReReview(generationId, snapshot, round);
    } catch (Exception e) {
      fail(generationId, snapshot.ownerId(), "Review after revert failed. Retry the Reviewing stage.");
    }
  }

  private List<AiGenerationPlanResult.PlannedFile> runPlanning(
      UUID generationId,
      GenerationService.GenerationSnapshot snapshot,
      GenerationSecrets secrets,
      int iteration) {
    boolean template = snapshot.aiProvider() == GenerationAiProvider.NONE;
    String promptVersion = template ? GenerationTemplates.TEMPLATE_VERSION : PROMPT_VERSION;
    String hash = GenerationService.inputHash(
        snapshot.requirement(), snapshot.backend().name(), snapshot.frontend().name(),
        snapshot.database().name(), snapshot.auth().name(), snapshot.integrations().toString(),
        snapshot.aiModel(), promptVersion, String.valueOf(iteration));
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.PLANNING, snapshot.aiModel(), promptVersion, hash);
    long startedNanos = System.nanoTime();
    try {
      AiGenerationPlanResult approvedPlan = generations.savedPlan(generationId, iteration);
      AiGenerationPlanResult result = approvedPlan != null
          ? approvedPlan
          : template
              ? GenerationTemplates.plan(snapshot.id().toString(), snapshot.backend(),
                  snapshot.frontend(), snapshot.database())
              : planFiles(snapshot, secrets);
      result = addRequiredProjectFiles(result, snapshot);
      validatePlan(result);
      UUID planId = approvedPlan != null
          ? generations.planId(generationId, iteration)
          : generations.savePlan(generationId, iteration, result);
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
    boolean template = snapshot.aiProvider() == GenerationAiProvider.NONE;
    String promptVersion = template ? GenerationTemplates.TEMPLATE_VERSION : PROMPT_VERSION;
    String hash = GenerationService.inputHash(
        snapshot.requirement(), snapshot.backend().name(), snapshot.frontend().name(),
        snapshot.database().name(), snapshot.auth().name(), snapshot.integrations().toString(),
        snapshot.aiModel(), promptVersion,
        String.valueOf(iteration), String.valueOf(plan.size()));
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.CODING, snapshot.aiModel(), promptVersion, hash);
    long startedNanos = System.nanoTime();
    try {
      List<AiGenerationFilesResult.GeneratedFile> generated = template
          ? GenerationTemplates.files(snapshot.backend(), snapshot.frontend(), snapshot.database(), plan)
          : generateFiles(snapshot, secrets, plan);
      generated = GenerationProjectScaffold.complete(
          generated,
          snapshot.backend(),
          snapshot.frontend(),
          snapshot.database(),
          envExample(snapshot));
      assertNoEmbeddedConnectionDetails(generated, snapshot);
      validateGeneratedFiles(generated, plan);
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

  private void validateGeneratedFiles(
      List<AiGenerationFilesResult.GeneratedFile> generated,
      List<AiGenerationPlanResult.PlannedFile> plan) {
    Set<String> expected = plan.stream().map(AiGenerationPlanResult.PlannedFile::path)
        .collect(java.util.stream.Collectors.toSet());
    Set<String> actual = generated.stream()
        .map(AiGenerationFilesResult.GeneratedFile::path)
        .collect(java.util.stream.Collectors.toSet());
    if (actual.size() != generated.size() || !actual.equals(expected)) {
      throw new GenerationException(
          "Generated files did not match the approved file list. Please review the plan and retry.");
    }
  }

  private AiGenerationPlanResult addRequiredProjectFiles(
      AiGenerationPlanResult result, GenerationService.GenerationSnapshot snapshot) {
    Map<String, AiGenerationPlanResult.PlannedFile> files = new LinkedHashMap<>();
    for (AiGenerationPlanResult.PlannedFile file : result.files()) {
      files.put(file.path(), file);
    }
    for (AiGenerationPlanResult.PlannedFile file : GenerationProjectScaffold.requiredPlan(
        snapshot.backend(), snapshot.frontend(), snapshot.database())) {
      files.putIfAbsent(file.path(), file);
    }
    AiGenerationPlanResult.PlanSections sections = result.sections() == null
        ? AiGenerationPlanResult.PlanSections.empty() : result.sections();
    return new AiGenerationPlanResult(
        result.generationId(), List.copyOf(files.values()), result.notes(), sections);
  }

  private String envExample(GenerationService.GenerationSnapshot snapshot) {
    StringBuilder env = new StringBuilder(
        "# Copy to .env and provide values locally. Never commit real credentials.\n");
    env.append("PORT=\n");
    if (snapshot.database() != GenerationDatabase.NONE) {
      env.append("DATABASE_URL=\n")
          .append("DATABASE_HOST=\n")
          .append("DATABASE_PORT=\n")
          .append("DATABASE_NAME=\n")
          .append("DATABASE_USERNAME=\n")
          .append("DATABASE_PASSWORD=\n")
          .append("DATABASE_SSL_MODE=\n");
    }
    if (snapshot.aiProvider() != GenerationAiProvider.NONE) {
      env.append("AI_API_KEY=\nAI_PROVIDER=\nAI_MODEL=\nAI_BASE_URL=\n");
    }
    return env.toString();
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
        snapshot.auth().name(),
        snapshot.integrations().stream().map(Enum::name).toList(),
        snapshot.dbHost(),
        snapshot.dbPort(),
        snapshot.dbName(),
        snapshot.dbUsername(),
        snapshot.dbSslMode(),
        snapshot.aiProvider().name(),
        secrets.aiApiKey(),
        snapshot.aiBaseUrl(),
        snapshot.aiModel(),
        snapshot.languageRuntime(),
        snapshot.backendCustom(),
        snapshot.frontendCustom(),
        snapshot.answers());
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
        snapshot.auth().name(),
        snapshot.integrations().stream().map(Enum::name).toList(),
        snapshot.dbHost(),
        snapshot.dbPort(),
        snapshot.dbName(),
        snapshot.dbUsername(),
        snapshot.dbSslMode(),
        snapshot.aiProvider().name(),
        secrets.aiApiKey(),
        snapshot.aiBaseUrl(),
        snapshot.aiModel(),
        snapshot.languageRuntime(),
        snapshot.backendCustom(),
        snapshot.frontendCustom(),
        snapshot.answers(),
        refs,
        null,
        List.of(),
        List.of());
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

  /**
   * A blank secret (skipped database password or API key) can never be
   * "embedded": {@code String.contains("")} is always true, so blank values
   * must be ignored or every generation would be rejected.
   */
  private void assertNoEmbeddedSecret(String content, String path, String secret) {
    if (secret != null && !secret.isBlank() && content.contains(secret)) {
      throw new GenerationException(
          "Generated file embeds credentials (" + path + "); secrets must use environment variables");
    }
  }

  private void assertNoEmbeddedConnectionDetails(
      List<AiGenerationFilesResult.GeneratedFile> generated,
      GenerationService.GenerationSnapshot snapshot) {
    for (AiGenerationFilesResult.GeneratedFile file : generated) {
      assertNoEmbeddedSecret(file.content(), file.path(), snapshot.dbHost());
      assertNoEmbeddedSecret(file.content(), file.path(), snapshot.aiBaseUrl());
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
    project.setLanguage(languageFor(snapshot));
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
    // Template workspaces are dependency-free: compile+check with the JDK
    // already in the image (the mount is read-only, so objects go to /tmp).
    String command = snapshot.aiProvider() == GenerationAiProvider.NONE
        ? GenerationTemplates.BUILD_COMMAND
        : buildCommandForStack(snapshot.backend(), snapshot.frontend(), snapshot.database());
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

    if (result.skipped()) {
      // Docker sandbox disabled: nothing was built or tested. Never SUCCESS.
      buildStatus = GenerationExecutionEvidence.BuildStatus.SKIPPED;
      testStatus = GenerationExecutionEvidence.TestStatus.SKIPPED;
      failureReason = "Not verified: Docker sandbox is disabled";
    } else if (result.timedOut()) {
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
   * Honest verification for a skipped sandbox run. The verdict is REJECTED
   * (never VERIFIED), the agent execution is SKIPPED, and the generation moves
   * to NOT_VERIFIED so the UI can say "Not verified" without pretending a
   * build or test happened.
   */
  private void runSkippedVerification(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration,
      GenerationExecutionEvidence evidence, UUID verificationRunId) {
    String hash = GenerationService.inputHash(
        snapshot.id().toString(), String.valueOf(iteration), "sandbox-skipped",
        evidence.getCommand());
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.VERIFIED, snapshot.aiModel(),
        GenerationTemplates.TEMPLATE_VERSION, hash);
    long startedNanos = System.nanoTime();
    generations.updateVerificationRun(verificationRunId,
        0, 0, 0, 0, VerificationVerdict.REJECTED, "sandbox-unavailable",
        millisSince(startedNanos));
    generations.finishAgentExecution(executionId, AgentExecutionStatus.SKIPPED,
        "Not verified: Docker sandbox is disabled", millisSince(startedNanos),
        "verification:" + verificationRunId);
    generations.markStatus(generationId, GenerationStatus.NOT_VERIFIED, null);
  }

  /**
   * Deterministic verdict for NONE-provider runs: no LLM is available, so the
   * verdict derives directly from the sandbox evidence (exit code 0 plus
   * successful build and test statuses). Recorded as a generation-owned
   * verification run with model {@code template}, exactly like AI verdicts.
   */
  private void runDeterministicVerifying(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration,
      GenerationExecutionEvidence evidence, UUID verificationRunId) {
    String hash = GenerationService.inputHash(
        snapshot.id().toString(), String.valueOf(iteration), "template-verify",
        String.valueOf(evidence.getExitCode()), String.valueOf(evidence.getBuildStatus()));
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.VERIFIED, "template", GenerationTemplates.TEMPLATE_VERSION, hash);
    long startedNanos = System.nanoTime();
    try {
      boolean green = evidence.getExitCode() == 0
          && evidence.getBuildStatus() == GenerationExecutionEvidence.BuildStatus.SUCCESS
          && evidence.getTestStatus() == GenerationExecutionEvidence.TestStatus.SUCCESS;
      VerificationVerdict verdict =
          green ? VerificationVerdict.VERIFIED : VerificationVerdict.REJECTED;
      generations.updateVerificationRun(verificationRunId,
          1, green ? 1 : 0, green ? 0 : 1, 0,
          verdict, "template-deterministic", millisSince(startedNanos));
      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos), "verification:" + verificationRunId);
      if (green) {
        generations.markStatus(generationId, GenerationStatus.VERIFIED, null);
      } else {
        fail(generationId, snapshot.ownerId(),
            "Verification failed: template checks did not pass");
      }
    } catch (Exception e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Verification failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      fail(generationId, snapshot.ownerId(), "Verification error: " + e.getMessage());
    }
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
    runReviewing(generationId, snapshot, iteration, true);
  }

  private void runReviewing(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration, boolean rateAfter) {
    // Start REVIEW agent execution
    String hash = GenerationService.inputHash(
        snapshot.id().toString(), String.valueOf(iteration), "review");
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.REVIEW, snapshot.aiModel(), PROMPT_VERSION, hash);
    long startedNanos = System.nanoTime();
    boolean completed = false;
    try {
      // Create a review record for this generation
      UUID reviewId = generations.createReview(generationId, iteration);

      // Link the review to the agent execution
      generations.linkReviewToAgentExecution(executionId, reviewId);

      // Read files from the generation workspace
      Path workspace = storage.generationWorkspaceDir(generationId, iteration);
      List<com.verireview.agent.dto.AiFileSnapshot> files = readWorkspaceFiles(workspace);

      List<GenerationStaticAnalyzer.StaticFinding> staticFindings =
          staticAnalyzer.analyze(readGeneratedFiles(workspace));
      staticFindings = mergeContractFindings(staticFindings, readGeneratedFiles(workspace), snapshot);
      List<com.verireview.agent.dto.AiDeterministicFinding> deterministic =
          staticFindings.stream()
              .map(finding -> new com.verireview.agent.dto.AiDeterministicFinding(
                  "generation-static",
                  "generation-static:" + finding.category(),
                  finding.file(),
                  finding.line(),
                  finding.explanation()))
              .toList();
      int staticAdded = generations.persistGenerationStaticFindings(reviewId, staticFindings);

      // Build review request
      com.verireview.agent.dto.AiReviewRequest request = new com.verireview.agent.dto.AiReviewRequest(
          reviewId.toString(),
          generationId.toString(),
          languageFor(snapshot),
          files,
          deterministic,
          "gen-review-" + reviewId);

      // Invoke Review Agent via AI service
      com.verireview.agent.dto.AiReviewResult result = reviewAiClient.review(request);

      // Persist AI findings using shared validation/dedup logic
      int added = generations.persistGenerationReviewFindings(
          reviewId, result, result.promptVersion(), aiServiceProperties.modelLabel());
      added += staticAdded;

      // Update review with results
      generations.updateReview(reviewId, ReviewStatus.COMPLETED, added, null);

      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos), "review:" + reviewId + " ai:" + added);

      // Transition to REVIEWED
      generations.markStatus(generationId, GenerationStatus.REVIEWED, null);
      completed = true;

    } catch (Exception e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Review failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      fail(generationId, snapshot.ownerId(), "Review error: " + e.getMessage());
    }
    if (completed && rateAfter) {
      runRating(generationId, snapshot, iteration);
      if (generations.findById(generationId).getStatus() != GenerationStatus.FAILED) {
        generations.finishReviewRound(generationId, iteration);
      }
    }
  }

  private void runRating(
      UUID generationId, GenerationService.GenerationSnapshot snapshot, int iteration) {
    String hash = GenerationService.inputHash(
        snapshot.id().toString(),
        String.valueOf(iteration),
        "rating");
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.RATING, "deterministic-rating-v1", "rating/v1", hash);
    long startedNanos = System.nanoTime();
    try {
      GenerationResponse.RatingView rating = generations.ratingForGeneration(generationId);
      generations.finishAgentExecution(
          executionId,
          AgentExecutionStatus.COMPLETED,
          null,
          millisSince(startedNanos),
          "overall:" + rating.overall());
    } catch (Exception e) {
      generations.finishAgentExecution(
          executionId,
          AgentExecutionStatus.FAILED,
          "Rating failed: " + messageOf(e),
          millisSince(startedNanos),
          null);
      fail(generationId, snapshot.ownerId(), "Rating failed. Retry generation to try again.");
    }
  }

  /**
   * Re-reviews the fixed workspace after a successful re-verification. The
   * Review Agent runs again against the current files and any new findings
   * are added to the generation's existing review (duplicates are skipped by
   * the shared dedup key), so issue counts stay continuous across fix rounds.
   *
   * <p>Immediately after that fresh scan, {@code resolveFixedFindings} closes
   * the loop: any Finding that had an active fix request and whose dedup key
   * no longer appears in the fresh scan is marked VERIFIED_FIXED and its
   * FixRequest(s) move to COMPLETED. A Finding that still reproduces is left
   * open for another fix round. This is what makes the "fixed" stat and the
   * download gate reflect reality instead of always reading zero.
   *
   * <p>The generation returns to REVIEWED; download unlocks only when the
   * backend gate finds no unresolved blocking findings.
   */
  private void runReReview(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration) {
    List<Review> previous = generations.reviews()
        .findByGenerationIdOrderByCreatedAtDesc(generationId);
    if (previous.isEmpty()) {
      runReviewing(generationId, snapshot, iteration);
      return;
    }
    Review existing = previous.get(0);
    UUID reviewId = existing.getId();
    int alreadyCounted = existing.getFindingCount();

    String hash = GenerationService.inputHash(
        snapshot.id().toString(), String.valueOf(iteration), "re-review");
    UUID executionId = generations.startAgentExecution(
        generationId, AgentType.REVIEW, snapshot.aiModel(), PROMPT_VERSION, hash);
    long startedNanos = System.nanoTime();
    boolean completed = false;
    try {
      generations.linkReviewToAgentExecution(executionId, reviewId);

      Path workspace = storage.generationWorkspaceDir(generationId, iteration);
      List<com.verireview.agent.dto.AiFileSnapshot> files = readWorkspaceFiles(workspace);
      List<GenerationStaticAnalyzer.StaticFinding> staticFindings =
          staticAnalyzer.analyze(readGeneratedFiles(workspace));
      staticFindings = mergeContractFindings(staticFindings, readGeneratedFiles(workspace), snapshot);
      List<com.verireview.agent.dto.AiDeterministicFinding> deterministic =
          staticFindings.stream()
              .map(finding -> new com.verireview.agent.dto.AiDeterministicFinding(
                  "generation-static",
                  "generation-static:" + finding.category(),
                  finding.file(),
                  finding.line(),
                  finding.explanation()))
              .toList();
      int staticAdded = generations.persistGenerationStaticFindings(reviewId, staticFindings);

      // The idempotency key must differ from the first review's key, or the
      // AI service may answer with its cached result for the unfixed files.
      com.verireview.agent.dto.AiReviewRequest request = new com.verireview.agent.dto.AiReviewRequest(
          reviewId.toString(),
          generationId.toString(),
          languageFor(snapshot),
          files,
          deterministic,
          "gen-rereview-" + reviewId + "-" + System.currentTimeMillis());

      com.verireview.agent.dto.AiReviewResult result = reviewAiClient.review(request);

      // Compute what the fresh scan reports BEFORE persisting, so the
      // comparison in resolveFixedFindings is against the new scan's own
      // findings, not against rows this same call is about to insert.
      Set<String> currentDedupKeys = generations.computeReportedDedupKeys(result);

      int added = generations.persistGenerationReviewFindings(
          reviewId, result, result.promptVersion(), aiServiceProperties.modelLabel());
      added += staticAdded;

      int resolved = generations.resolveFixedFindings(reviewId, currentDedupKeys);

      generations.updateReview(reviewId, ReviewStatus.COMPLETED, alreadyCounted + added, null);

      generations.finishAgentExecution(executionId, AgentExecutionStatus.COMPLETED,
          null, millisSince(startedNanos),
          "re-review:" + reviewId + " new:" + added + " fixed:" + resolved);

      generations.markStatus(generationId, GenerationStatus.REVIEWED, null);
      completed = true;

    } catch (Exception e) {
      generations.finishAgentExecution(executionId, AgentExecutionStatus.FAILED,
          truncate("Re-review failed: " + e.getMessage(), 2000),
          millisSince(startedNanos), null);
      fail(generationId, snapshot.ownerId(), "Re-review error: " + e.getMessage());
    }
    if (completed) {
      runRating(generationId, snapshot, iteration);
      if (generations.findById(generationId).getStatus() != GenerationStatus.FAILED) {
        generations.finishReviewRound(generationId, iteration);
      }
    }
  }

  /**
   * Runs the rebuild and re-verification flow for a generation after a patch
   * has been applied, then re-reviews the fixed files. Uses the current
   * iteration and the modified generation workspace.
   *
   * @param generationId the generation to rebuild
   * @param snapshot the generation snapshot
   * @param iteration the current iteration whose workspace was patched
   */
  private void runRebuildAndReverify(UUID generationId,
      GenerationService.GenerationSnapshot snapshot, int iteration) {
    // The generation is already in REBUILDING (see triggerRebuildAndReverify).
    UUID evidenceId = runBuilding(generationId, snapshot, iteration);
    GenerationExecutionEvidence evidence = generations.executionEvidence()
        .findById(evidenceId).orElseThrow();

    if (evidence.getBuildStatus() == GenerationExecutionEvidence.BuildStatus.SKIPPED) {
      generations.markStatus(generationId, GenerationStatus.REVERIFYING, null);
      UUID skippedRunId = generations.createVerificationRun(
          generationId, iteration, evidenceId);
      runSkippedVerification(generationId, snapshot, iteration, evidence, skippedRunId);
      generations.markStatus(generationId, GenerationStatus.REVIEWING, null);
      runReReview(generationId, snapshot, iteration);
      return;
    }

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

    // Template (NONE-provider) projects have no LLM: verify from the evidence.
    if (snapshot.aiProvider() == GenerationAiProvider.NONE) {
      runDeterministicVerifying(generationId, snapshot, iteration, evidence, verificationRunId);
    } else {
      runVerifying(generationId, snapshot, iteration, evidence, verificationRunId);
    }

    // Successful verification transitions to VERIFIED; then the Review Agent
    // re-verifies the fixed files and the generation returns to REVIEWED.
    Generation after = generations.findById(generationId);
    if (after.getStatus() == GenerationStatus.VERIFIED) {
      generations.markStatus(generationId, GenerationStatus.REVIEWING, null);
      runReReview(generationId, snapshot, iteration);
    }
  }

  /**
   * Reads files from the generation workspace for review.
   */
  private List<com.verireview.agent.dto.AiFileSnapshot> readWorkspaceFiles(Path workspace) {
    List<com.verireview.agent.dto.AiFileSnapshot> snapshots = new ArrayList<>();
    if (!Files.isDirectory(workspace)) {
      return List.of();
    }

    try (var paths = Files.walk(workspace)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        if (Files.isSymbolicLink(file)) {
          continue;
        }

        String relativePath = workspace.relativize(file).toString().replace('\\', '/');
        String content = Files.readString(file);
        if (content.indexOf('\0') < 0 && !content.isBlank()) {
          snapshots.add(new com.verireview.agent.dto.AiFileSnapshot(
              relativePath, languageOf(relativePath, null), content, false));
        }
      }
      return List.copyOf(snapshots);
    } catch (Exception e) {
      throw new GenerationException("Review could not read generated files", e);
    }
  }

  private List<AiGenerationFilesResult.GeneratedFile> readGeneratedFiles(Path workspace) {
    List<AiGenerationFilesResult.GeneratedFile> files = new ArrayList<>();
    if (!Files.isDirectory(workspace)) {
      return List.of();
    }
    try (var paths = Files.walk(workspace)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        if (Files.isSymbolicLink(file)) {
          continue;
        }
        String relative = workspace.relativize(file).toString().replace('\\', '/');
        String content = Files.readString(file);
        if (content.indexOf('\0') < 0 && !content.isBlank()) {
          files.add(new AiGenerationFilesResult.GeneratedFile(
              relative, content, languageOf(relative, null)));
        }
      }

      return List.copyOf(files);
    } catch (Exception e) {
      throw new GenerationException("Static checks could not read generated files", e);
    }
  }

  private List<GenerationStaticAnalyzer.StaticFinding> mergeContractFindings(
      List<GenerationStaticAnalyzer.StaticFinding> syntaxFindings,
      List<AiGenerationFilesResult.GeneratedFile> files,
      GenerationService.GenerationSnapshot snapshot) {
    List<GenerationStaticAnalyzer.StaticFinding> all = new ArrayList<>(syntaxFindings);
    all.addAll(staticAnalyzer.analyzeProjectContract(
        files, snapshot.backend(), snapshot.frontend(), snapshot.database()));
    return List.copyOf(all);
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
    if (frontend == GenerationFrontend.REACT_TYPESCRIPT
        || frontend == GenerationFrontend.NEXTJS
        || frontend == GenerationFrontend.VUE) {
      return "npm test";
    }
    // Database-only projects keep the Python toolchain check.
    if (database != GenerationDatabase.NONE && backend == GenerationBackend.NONE) {
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
      case OTHER, NONE -> null;
    };
  }

  /** Explicit language/runtime wins; the backend enum is only a fallback. */
  static String languageFor(GenerationService.GenerationSnapshot snapshot) {
    String runtime = snapshot.languageRuntime();
    return runtime == null || runtime.isBlank()
        ? primaryLanguage(snapshot.backend())
        : runtime.trim();
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