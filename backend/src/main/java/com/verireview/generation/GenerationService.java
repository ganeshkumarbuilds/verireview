package com.verireview.generation;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionRepository;
import com.verireview.agent.AgentExecutionStatus;
import com.verireview.agent.AgentType;
import com.verireview.agent.dto.AiGenerationPlanResult;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.audit.AuditService;
import com.verireview.analysis.Fingerprint;
import com.verireview.common.PagedResponse;
import com.verireview.generation.dto.CreateGenerationRequest;
import com.verireview.generation.dto.CreateGenerationRevisionRequest;
import com.verireview.generation.dto.GenerationResponse;
import com.verireview.generation.dto.GenerationRevisionResponse;
import com.verireview.generation.dto.StartGenerationRequest;
import com.verireview.generation.dto.UpdateGenerationRequest;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.user.UserRepository;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.FindingStatus;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import com.verireview.verification.VerificationRun;
import com.verireview.verification.VerificationRunRepository;
import com.verireview.verification.VerificationVerdict;
import com.verireview.verification.BuildStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Generation control plane. Creates the QUEUED job, validates the wizard
 * input (explicit stack choices, conditional database/AI requirements), and
 * hands execution to {@link GenerationRunner}. Short transactional
 * boundaries only — the minutes-long AI run lives in the async worker.
 *
 * <p>Secrets ({@code password}, {@code apiKey}) are held in the in-memory
 * {@code secrets} map only for the run and are never persisted, logged, or
 * returned. The {@link Generation} row has no secret columns by design.
 * Drafts ({@code DRAFT}) persist configuration only and hold no secrets at
 * all — secure secret storage is pending, so starting a draft always
 * requires re-supplying them.
 */
@Service
public class GenerationService {

  private final GenerationRepository generations;
  private final GenerationRevisionRepository revisions;
  private final GenerationArtifactRepository artifacts;
  private final GenerationPlanRepository plans;
  private final GenerationExecutionEvidenceRepository executionEvidence;
  private final AgentExecutionRepository executions;
  private final VerificationRunRepository verificationRuns;
  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final UserRepository users;
  private final ProjectRepository projects;
  private final GenerationRunner runner;
  private final AuditService audits;
  private final ObjectMapper objects;
  private final ConcurrentMap<UUID, GenerationSecrets> secrets = new ConcurrentHashMap<>();

  public GenerationService(
      GenerationRepository generations,
      GenerationRevisionRepository revisions,
      GenerationArtifactRepository artifacts,
      GenerationPlanRepository plans,
      GenerationExecutionEvidenceRepository executionEvidence,
      AgentExecutionRepository executions,
      VerificationRunRepository verificationRuns,
      ReviewRepository reviews,
      FindingRepository findings,
      UserRepository users,
      ProjectRepository projects,
      @Lazy GenerationRunner runner,
      AuditService audits,
      ObjectMapper objects) {
    this.generations = generations;
    this.revisions = revisions;
    this.artifacts = artifacts;
    this.plans = plans;
    this.executionEvidence = executionEvidence;
    this.executions = executions;
    this.verificationRuns = verificationRuns;
    this.reviews = reviews;
    this.findings = findings;
    this.users = users;
    this.projects = projects;
    this.runner = runner;
    this.audits = audits;
    this.objects = objects;
  }

  public GenerationExecutionEvidenceRepository executionEvidence() {
    return executionEvidence;
  }

  public ReviewRepository reviews() {
    return reviews;
  }

  @Transactional
  public GenerationResponse create(UUID ownerId, CreateGenerationRequest request) {
    String name = request.name().trim();
    if (generations.existsByOwnerIdAndName(ownerId, name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation name is already used");
    }
    boolean draft = Boolean.TRUE.equals(request.draft());
    checkStackCoherence(request.database(), request.databaseConfig() != null,
        request.aiConfig().provider(), request.aiConfig().baseUrl());
    if (!draft) {
      requireSecrets(request);
    }

    Generation generation = new Generation();
    generation.setOwner(users.getReferenceById(ownerId));
    generation.setName(name);
    generation.setRequirement(request.requirement().trim());
    generation.setDescription(blankToNull(request.description()));
    generation.setBackendStack(request.backend());
    generation.setFrontendStack(request.frontend());
    generation.setDatabaseType(request.database());
    if (request.database() != GenerationDatabase.NONE) {
      CreateGenerationRequest.DatabaseConfig db = request.databaseConfig();
      generation.setDbHost(db.host().trim());
      generation.setDbPort(db.port());
      generation.setDbName(db.name().trim());
      generation.setDbUsername(db.username().trim());
      generation.setDbSslMode(blankToNull(db.sslMode()));
    }
    generation.setAiProvider(request.aiConfig().provider());
    generation.setAiModel(request.aiConfig().model().trim());
    generation.setAiBaseUrl(blankToNull(request.aiConfig().baseUrl()));
    generation.setStatus(draft ? GenerationStatus.DRAFT : GenerationStatus.QUEUED);
    try {
      generations.saveAndFlush(generation);
      revisions.save(new GenerationRevision(
          generation, 1, generation.getRequirement()));
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation name is already used");
    }

    if (!draft) {
      // In-memory only: never persisted, never logged, never returned.
      String dbPassword = request.database() != GenerationDatabase.NONE
          ? request.databaseConfig().password() : null;
      secrets.put(generation.getId(),
          new GenerationSecrets(dbPassword, request.aiConfig().apiKey()));
    }

    audits.record(generation.getOwner(), "GENERATION_CREATED", "generation",
        generation.getId().toString());
    if (!draft) {
      dispatchAfterCommit(generation.getId());
    }
    return toResponse(generation);
  }

  /**
   * Starts a draft: validates the supplied secrets, moves DRAFT → READY, and
   * dispatches the existing runner. Only drafts can be started; anything else
   * is a 409. Secrets travel in the body only and are never persisted.
   */
  @Transactional
  public GenerationResponse start(UUID ownerId, UUID generationId, StartGenerationRequest request) {
    Generation generation = owned(ownerId, generationId);
    if (generation.getStatus() != GenerationStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only drafts can be started");
    }
    boolean wantsDb = generation.getDatabaseType() != GenerationDatabase.NONE;
    String password = request == null ? null : request.password();
    String apiKey = request == null ? null : request.apiKey();
    if (wantsDb && (password == null || password.isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database password is required to start");
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "AI API key is required to start");
    }
    generation.setStatus(GenerationStatus.READY);
    secrets.put(generation.getId(), new GenerationSecrets(password, apiKey));
    audits.record(generation.getOwner(), "GENERATION_STARTED", "generation",
        generation.getId().toString());
    dispatchAfterCommit(generation.getId());
    return toResponse(generation);
  }

  /**
   * Edits a draft's task and configuration. Drafts only — once a job leaves
   * DRAFT its configuration is frozen. The DTO carries no secret fields, so
   * secrets can never be set or rotated through this contract.
   */
  @Transactional
  public GenerationResponse update(
      UUID ownerId, UUID generationId, UpdateGenerationRequest request) {
    Generation generation = owned(ownerId, generationId);
    if (generation.getStatus() != GenerationStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only drafts can be edited");
    }
    if (request == null) {
      return toResponse(generation);
    }
    if (request.name() != null) {
      String name = request.name().trim();
      if (name.isEmpty() || name.length() > 200) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project name");
      }
      if (!name.equals(generation.getName())
          && generations.existsByOwnerIdAndName(ownerId, name)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation name is already used");
      }
      generation.setName(name);
    }
    if (request.requirement() != null) {
      String requirement = request.requirement().trim();
      if (requirement.isEmpty() || request.requirement().length() > 20000) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid requirement");
      }
      generation.setRequirement(requirement);
    }
    if (request.description() != null) {
      if (request.description().length() > 5000) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description is too long");
      }
      generation.setDescription(request.description().isBlank() ? null : request.description());
    }
    if (request.backend() != null) {
      generation.setBackendStack(request.backend());
    }
    if (request.frontend() != null) {
      generation.setFrontendStack(request.frontend());
    }
    GenerationDatabase database = generation.getDatabaseType();
    if (request.database() != null) {
      database = request.database();
      generation.setDatabaseType(database);
    }
    UpdateGenerationRequest.DatabaseConfig db = request.databaseConfig();
    if (database == GenerationDatabase.NONE) {
      if (db != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Database configuration must be omitted when no database is selected");
      }
      generation.setDbHost(null);
      generation.setDbPort(null);
      generation.setDbName(null);
      generation.setDbUsername(null);
      generation.setDbSslMode(null);
    } else if (db != null) {
      if (db.host() != null) {
        if (db.host().isBlank() || db.host().length() > 500) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid database host");
        }
        generation.setDbHost(db.host().trim());
      }
      if (db.port() != null) {
        generation.setDbPort(db.port());
      }
      if (db.name() != null) {
        if (db.name().isBlank() || db.name().length() > 200) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid database name");
        }
        generation.setDbName(db.name().trim());
      }
      if (db.username() != null) {
        if (db.username().isBlank() || db.username().length() > 200) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid database username");
        }
        generation.setDbUsername(db.username().trim());
      }
      if (db.sslMode() != null) {
        if (db.sslMode().length() > 50) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid SSL mode");
        }
        generation.setDbSslMode(db.sslMode().isBlank() ? null : db.sslMode().trim());
      }
    }
    if (database != GenerationDatabase.NONE
        && (generation.getDbHost() == null || generation.getDbHost().isBlank()
            || generation.getDbPort() == null
            || generation.getDbName() == null || generation.getDbName().isBlank()
            || generation.getDbUsername() == null || generation.getDbUsername().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database configuration is required for the selected database");
    }
    UpdateGenerationRequest.AiConfig ai = request.aiConfig();
    if (ai != null) {
      if (ai.provider() != null) {
        generation.setAiProvider(ai.provider());
      }
      if (ai.model() != null) {
        if (ai.model().isBlank() || ai.model().length() > 200) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid AI model");
        }
        generation.setAiModel(ai.model().trim());
      }
      if (ai.baseUrl() != null) {
        if (ai.baseUrl().length() > 500) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid AI base URL");
        }
        generation.setAiBaseUrl(ai.baseUrl().isBlank() ? null : ai.baseUrl().trim());
      }
    }
    if (generation.getAiProvider() == GenerationAiProvider.CUSTOM
        && (generation.getAiBaseUrl() == null || generation.getAiBaseUrl().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Base URL is required for a custom provider");
    }
    audits.record(generation.getOwner(), "GENERATION_UPDATED", "generation",
        generation.getId().toString());
    return toResponse(generation);
  }

  /**
   * Latest generation linked to a project, for the project workspace panel.
   * Owner-checked through the project: foreign or deleted projects answer
   * 404, as do projects with no generation yet.
   */
  @Transactional(readOnly = true)
  public GenerationResponse forProject(UUID ownerId, UUID projectId) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    return generations.findFirstByProjectIdOrderByCreatedAtDesc(project.getId())
        .map(this::toResponse)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No generation found for project"));
  }

  private void checkStackCoherence(
      GenerationDatabase database, boolean hasDatabaseConfig,
      GenerationAiProvider provider, String baseUrl) {
    boolean wantsDb = database != GenerationDatabase.NONE;
    if (wantsDb && !hasDatabaseConfig) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database configuration is required for the selected database");
    }
    if (!wantsDb && hasDatabaseConfig) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database configuration must be omitted when no database is selected");
    }
    if (provider == GenerationAiProvider.CUSTOM && (baseUrl == null || baseUrl.isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Base URL is required for a custom provider");
    }
  }

  private void requireSecrets(CreateGenerationRequest request) {
    boolean wantsDb = request.database() != GenerationDatabase.NONE;
    if (wantsDb && (request.databaseConfig().password() == null
        || request.databaseConfig().password().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database password is required");
    }
    if (request.aiConfig().apiKey() == null || request.aiConfig().apiKey().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "AI API key is required");
    }
  }

  /**
   * Hands the job to the async runner only after the creation transaction
   * commits. Dispatching inline would let the worker's snapshot read race
   * the commit and silently miss the row, stranding the job in QUEUED.
   */
  private void dispatchAfterCommit(UUID generationId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          runner.runAsync(generationId);
        }
      });
    } else {
      runner.runAsync(generationId);
    }
  }

  @Transactional(readOnly = true)
  public GenerationResponse get(UUID ownerId, UUID generationId) {
    return toResponse(owned(ownerId, generationId));
  }

  @Transactional(readOnly = true)
  public Generation findById(UUID generationId) {
    return generations.findById(generationId).orElseThrow();
  }

  @Transactional(readOnly = true)
  public PagedResponse<GenerationResponse> list(UUID ownerId, Pageable pageable) {
    Page<Generation> page = generations.findByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);
    return PagedResponse.of(page.map(this::toResponse));
  }

  /** Detached snapshot for the worker (no lazy loading outside transactions). */
  @Transactional(readOnly = true)
  public GenerationSnapshot snapshot(UUID generationId) {
    Generation generation = generations.findById(generationId).orElse(null);
    if (generation == null) {
      return null;
    }
    return new GenerationSnapshot(
        generation.getId(),
        generation.getOwner().getId(),
        generation.getName(),
        generation.getRequirement(),
        generation.getDescription(),
        generation.getBackendStack(),
        generation.getFrontendStack(),
        generation.getDatabaseType(),
        generation.getDbHost(),
        generation.getDbPort(),
        generation.getDbName(),
        generation.getDbUsername(),
        generation.getDbSslMode(),
        generation.getAiProvider(),
        generation.getAiModel(),
        generation.getAiBaseUrl());
  }

  public record GenerationSnapshot(
      UUID id,
      UUID ownerId,
      String name,
      String requirement,
      String description,
      GenerationBackend backend,
      GenerationFrontend frontend,
      GenerationDatabase database,
      String dbHost,
      Integer dbPort,
      String dbName,
      String dbUsername,
      String dbSslMode,
      GenerationAiProvider aiProvider,
      String aiModel,
      String aiBaseUrl) {
  }

  /** Consumes and discards the run secrets (single delivery). */
  GenerationSecrets takeSecrets(UUID generationId) {
    return secrets.remove(generationId);
  }

  /**
   * Appends a task modification as a new revision and makes it the current
   * task. Drafts only in this phase — once a job leaves DRAFT, mid-run
   * revision intake belongs to a later phase (the runner snapshots the task
   * at dispatch, so a concurrent edit would otherwise be silently ignored).
   */
  @Transactional
  public GenerationRevisionResponse appendRevision(
      UUID ownerId, UUID generationId, CreateGenerationRevisionRequest request) {
    Generation generation = owned(ownerId, generationId);
    if (generation.getStatus() != GenerationStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Task can only be modified while the generation is a draft");
    }
    String requirement = request.requirement().trim();
    long count = revisions.countByGenerationId(generation.getId());
    GenerationRevision revision = new GenerationRevision(
        generation, (int) count + 1, requirement);
    revisions.save(revision);
    generation.setRequirement(requirement);
    audits.record(generation.getOwner(), "GENERATION_REVISION_ADDED", "generation",
        generation.getId().toString());
    return toRevisionResponse(revision);
  }

  @Transactional(readOnly = true)
  public List<GenerationRevisionResponse> listRevisions(UUID ownerId, UUID generationId) {
    Generation generation = owned(ownerId, generationId);
    return revisions.findByGenerationIdOrderByRevisionNumberAsc(generation.getId()).stream()
        .map(GenerationService::toRevisionResponse)
        .toList();
  }

  /**
   * Starts the next fix-loop iteration. Throws 409 once the iteration budget
   * is exhausted so an endless agent loop is impossible.
   *
   * @return the new (1-based) iteration number
   */
  @Transactional
  public int nextIteration(UUID generationId) {
    Generation managed = generations.findById(generationId).orElseThrow();
    if (managed.getIteration() >= managed.getMaxIterations()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Maximum fix-loop iterations reached");
    }
    managed.setIteration(managed.getIteration() + 1);
    return managed.getIteration();
  }

  /**
   * Records verified-artifact facts for an iteration. Metadata only — file
   * contents stay on disk and server paths never leave the backend.
   *
   * @return the persisted artifact id for execution cross-references
   */
  @Transactional
  public UUID recordArtifact(UUID generationId, int iteration, int fileCount,
      long totalChars, String sha256, String storageRef) {
    Generation managed = generations.findById(generationId).orElseThrow();
    return artifacts.save(new GenerationArtifact(
        managed, iteration, fileCount, totalChars, sha256, storageRef)).getId();
  }

  /**
   * Persists the validated project plan for an iteration so the run can be
   * resumed and inspected without re-invoking the Planning Agent. The plan
   * JSON carries architecture, dependencies, directories, APIs, steps and
   * files — never secrets.
   *
   * @return the persisted plan row id for execution cross-references
   */
  @Transactional
  public UUID savePlan(UUID generationId, int iteration, AiGenerationPlanResult plan) {
    Generation managed = generations.findById(generationId).orElseThrow();
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("architecture", plan.sections().architecture());
    root.put("dependencies", plan.sections().dependencies());
    root.put("directories", plan.sections().directories());
    root.put("apis", plan.sections().apis());
    root.put("steps", plan.sections().steps());
    List<Object> files = new ArrayList<>();
    for (AiGenerationPlanResult.PlannedFile file : plan.files()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("path", file.path());
      item.put("purpose", file.purpose());
      files.add(item);
    }
    root.put("files", files);
    root.put("notes", plan.notes());
    final String planJson;
    try {
      planJson = objects.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode generation plan", e);
    }
    return plans.save(new GenerationPlan(
        managed, iteration, planJson, plan.files().size())).getId();
  }

  /**
   * Records one agent invocation against a generation. The project link stays
   * empty until materialization — planning and coding happen before any
   * project exists. The input hash covers non-secret inputs only.
   *
   * @return the persisted execution id
   */
  @Transactional
  public UUID startAgentExecution(UUID generationId, AgentType agentType, String model,
      String promptVersion, String inputHash) {
    Generation managed = generations.findById(generationId).orElseThrow();
    AgentExecution execution = new AgentExecution();
    execution.setGeneration(managed);
    execution.setAgentType(agentType);
    execution.setModel(model);
    execution.setPromptVersion(promptVersion);
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setInputHash(inputHash);
    return executions.save(execution).getId();
  }

  /** Closes an agent execution with its outcome. Error text must stay secret-free. */
  @Transactional
  public void finishAgentExecution(UUID executionId, AgentExecutionStatus status,
      String error, Long durationMs, String outputRef) {
    AgentExecution execution = executions.findById(executionId).orElseThrow();
    execution.setStatus(status);
    execution.setError(error);
    execution.setDurationMs(durationMs);
    execution.setOutputRef(outputRef);
  }

  @Transactional(readOnly = true)
  public AgentExecution getAgentExecution(UUID executionId) {
    return executions.findById(executionId).orElseThrow();
  }

  @Transactional(readOnly = true)
  public Review getReview(UUID reviewId) {
    return reviews.findById(reviewId).orElseThrow();
  }

  @Transactional
  public void linkReviewToAgentExecution(UUID executionId, UUID reviewId) {
    AgentExecution execution = executions.findById(executionId).orElseThrow();
    Review review = reviews.findById(reviewId).orElseThrow();
    execution.setReview(review);
  }

  /**
   * Records build/test execution evidence for a generation iteration.
   * Persisted facts only — no secrets, no source code.
   *
   * @return the persisted evidence id
   */
  @Transactional
  public UUID recordExecutionEvidence(UUID generationId, int iteration, String command,
      int exitCode, long durationMs, String stdout, String stderr,
      GenerationExecutionEvidence.BuildStatus buildStatus,
      GenerationExecutionEvidence.TestStatus testStatus,
      String failureReason) {
    Generation managed = generations.findById(generationId).orElseThrow();
    GenerationExecutionEvidence evidence = new GenerationExecutionEvidence(
        managed, iteration, command, exitCode, durationMs,
        stdout, stderr, buildStatus, testStatus, failureReason);
    return executionEvidence.save(evidence).getId();
  }

  /**
   * Creates a verification run for a generation iteration, linked to its
   * execution evidence. The verdict is set by the Verified Agent evaluation.
   *
   * @return the persisted verification run id
   */
  @Transactional
  public UUID createVerificationRun(UUID generationId, int iteration,
      UUID executionEvidenceId) {
    Generation managed = generations.findById(generationId).orElseThrow();
    GenerationExecutionEvidence evidence = executionEvidence.findById(executionEvidenceId).orElseThrow();
    VerificationRun run = new VerificationRun();
    run.setGeneration(managed);
    run.setBuildStatus(BuildStatus.valueOf(evidence.getBuildStatus().name()));
    run.setTestsTotal(0);
    run.setTestsPassed(0);
    run.setTestsFailed(0);
    run.setTestsSkipped(0);
    run.setVerdict(VerificationVerdict.PENDING);
    run.setDurationMs(evidence.getDurationMs());
    return verificationRuns.save(run).getId();
  }

  /**
   * Updates a generation's verification run with the Verified Agent's verdict.
   */
  @Transactional
  public void updateVerificationRun(UUID verificationRunId,
      int testsTotal, int testsPassed, int testsFailed, int testsSkipped,
      VerificationVerdict verdict, String logRef, Long durationMs) {
    VerificationRun run = verificationRuns.findById(verificationRunId).orElseThrow();
    run.setTestsTotal(testsTotal);
    run.setTestsPassed(testsPassed);
    run.setTestsFailed(testsFailed);
    run.setTestsSkipped(testsSkipped);
    run.setVerdict(verdict);
    run.setLogRef(logRef);
    if (durationMs != null) {
      run.setDurationMs(durationMs);
    }
  }

  /** Stable, secret-free fingerprint of an agent invocation's inputs. */
  public static String inputHash(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        if (part != null) {
          digest.update(part.getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * Creates a review for a generation iteration, linked to the generation.
   * The review can later be populated with AI findings.
   *
   * @return the persisted review id
   */
  @Transactional
  public UUID createReview(UUID generationId, int iteration) {
    Generation managed = generations.findById(generationId).orElseThrow();
    Review review = new Review(null); // Project will be set after materialization
    review.setGeneration(managed);
    review.setStatus(ReviewStatus.QUEUED);
    return reviews.save(review).getId();
  }

  /**
   * Updates a generation's review with status and finding count.
   */
  @Transactional
  public void updateReview(UUID reviewId, ReviewStatus status, int findingCount, String error) {
    Review review = reviews.findById(reviewId).orElseThrow();
    review.setStatus(status);
    review.setFindingCount(findingCount);
    review.setError(error);
  }

  /**
   * Persists AI findings from a generation review. Reuses the validation and
   * deduplication logic from {@link com.verireview.agent.AiReviewService}.
   * VERIFIED-source findings are coerced to AI (backend never lets AI self-verify).
   * Deterministic echoes are skipped.
   *
   * @return number of findings actually persisted
   */
  @Transactional
  public int persistGenerationReviewFindings(UUID reviewId, AiReviewResult result,
      String promptVersion, String modelLabel) {
    Review review = reviews.findById(reviewId).orElseThrow();
    List<Finding> rows = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int skippedInvalid = 0;
    int skippedEchoes = 0;
    boolean verifiedCoerced = false;
    for (AiProposedFinding item : result.findings()) {
      if (!"AI".equalsIgnoreCase(item.source())
          && !"VERIFIED".equalsIgnoreCase(item.source())) {
        if ("DETERMINISTIC".equalsIgnoreCase(item.source())) {
          skippedEchoes++;
          continue;
        }
        skippedInvalid++;
        continue;
      }
      FindingCategory category;
      FindingSeverity severity;
      try {
        category = FindingCategory.valueOf(item.category().trim().toUpperCase());
        severity = FindingSeverity.valueOf(item.severity().trim().toUpperCase());
      } catch (Exception e) {
        skippedInvalid++;
        continue;
      }
      if (item.title() == null || item.title().isBlank()) {
        skippedInvalid++;
        continue;
      }
      if ("VERIFIED".equalsIgnoreCase(item.source())) {
        verifiedCoerced = true;
      }
      String title = trim(item.title(), 500);
      String filePath = trim(item.filePath(), 1000);
      Integer lineStart = nonNegative(item.lineStart());
      Integer lineEnd = nonNegative(item.lineEnd());
      String key = Fingerprint.of(
          "review-agent", title, filePath, lineStart, item.description());
      if (!seen.add(key) || findings.existsByReviewIdAndDedupKey(review.getId(), key)) {
        continue;
      }
      Finding row = new Finding(review, category, severity, FindingSource.AI, title);
      row.setDescription(trim(item.description(), 5000));
      row.setFilePath(filePath);
      row.setLineStart(lineStart);
      row.setLineEnd(lineEnd);
      row.setEvidence(evidenceJson(item, promptVersion, modelLabel));
      row.setDedupKey(key);
      row.setStatus(FindingStatus.OPEN);
      rows.add(row);
    }
    if (!rows.isEmpty()) {
      findings.saveAll(rows);
    }
    return rows.size();
  }

  private String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static Integer nonNegative(Integer value) {
    return value != null && value >= 0 ? value : null;
  }

  private String evidenceJson(AiProposedFinding item, String promptVersion, String modelLabel) {
    try {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("analyzer", "review-agent");
      evidence.put("model", modelLabel);
      evidence.put("promptVersion", promptVersion);
      evidence.put("confidence", item.confidence());
      evidence.put("suggestedFixHint", trim(item.suggestedFixHint(), 2000));
      evidence.put("claimedSource", item.source());
      return objects.writeValueAsString(evidence);
    } catch (Exception e) {
      return "{}";
    }
  }

  @Transactional
  public void markStatus(UUID generationId, GenerationStatus status, String error) {
    Generation managed = generations.findById(generationId).orElseThrow();
    transition(managed, status);
    managed.setError(error);
  }

  @Transactional
  public void complete(UUID generationId, UUID projectId) {
    Generation managed = generations.findById(generationId).orElseThrow();
    Project project = projects.findById(projectId).orElseThrow();
    managed.setProject(project);
    transition(managed, GenerationStatus.COMPLETED);
    managed.setError(null);
  }

  private void transition(Generation managed, GenerationStatus next) {
    if (!managed.getStatus().canTransitionTo(next)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Illegal generation transition from " + managed.getStatus() + " to " + next);
    }
    managed.setStatus(next);
  }

  private Generation owned(UUID ownerId, UUID generationId) {
    return generations.findByIdAndOwnerId(generationId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation not found"));
  }

  GenerationResponse toResponse(Generation generation) {
    int revisionNumber = revisions
        .findFirstByGenerationIdOrderByRevisionNumberDesc(generation.getId())
        .map(GenerationRevision::getRevisionNumber)
        .orElse(1);
    long revisionCount = revisions.countByGenerationId(generation.getId());
    GenerationArtifact artifact = artifacts
        .findFirstByGenerationIdOrderByCreatedAtDesc(generation.getId())
        .orElse(null);
    GenerationResponse.PlanView plan = plans
        .findFirstByGenerationIdOrderByIterationDesc(generation.getId())
        .map(this::toPlanView)
        .orElse(null);
    return toResponse(generation, revisionNumber, revisionCount, artifact, plan);
  }

  private GenerationResponse.PlanView toPlanView(GenerationPlan plan) {
    try {
      JsonNode root = objects.readTree(plan.getPlanJson());
      return new GenerationResponse.PlanView(
          plan.getIteration(),
          plan.getFileCount(),
          textOrEmpty(root.get("architecture")),
          strings(root.get("dependencies")),
          strings(root.get("directories")),
          textOrEmpty(root.get("apis")),
          strings(root.get("steps")));
    } catch (Exception e) {
      // Our own write path produced this JSON; never break status polling
      // over a read-back problem.
      return null;
    }
  }

  private static String textOrEmpty(JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual()) {
      return "";
    }
    return node.asText();
  }

  private static List<String> strings(JsonNode node) {
    List<String> items = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual()) {
          items.add(item.asText());
        }
      }
    }
    return List.copyOf(items);
  }

  private static GenerationResponse toResponse(Generation generation, int revisionNumber,
      long revisionCount, GenerationArtifact artifact, GenerationResponse.PlanView plan) {
    // Drafts hold no secrets server-side (secure secret storage is pending),
    // so their configured flags stay false until a start supplies them.
    boolean sealed = generation.getStatus() != GenerationStatus.DRAFT;
    GenerationResponse.DatabaseView db = null;
    if (generation.getDatabaseType() != GenerationDatabase.NONE) {
      db = new GenerationResponse.DatabaseView(
          generation.getDbHost(),
          generation.getDbPort(),
          generation.getDbName(),
          generation.getDbUsername(),
          generation.getDbSslMode(),
          sealed);
    }
    GenerationResponse.AiView ai = new GenerationResponse.AiView(
        generation.getAiProvider(),
        generation.getAiModel(),
        generation.getAiBaseUrl(),
        sealed);
    GenerationResponse.ArtifactView artifactView = artifact == null ? null
        : new GenerationResponse.ArtifactView(
            artifact.getId(),
            artifact.getIteration(),
            artifact.getFileCount(),
            artifact.getTotalChars(),
            artifact.getSha256(),
            artifact.getCreatedAt());
    return new GenerationResponse(
        generation.getId(),
        generation.getName(),
        generation.getRequirement(),
        generation.getDescription(),
        generation.getBackendStack(),
        generation.getFrontendStack(),
        generation.getDatabaseType(),
        db,
        ai,
        generation.getStatus(),
        generation.getError(),
        generation.getProject() != null ? generation.getProject().getId() : null,
        generation.getIteration(),
        generation.getMaxIterations(),
        revisionNumber,
        revisionCount,
        artifactView,
        plan,
        generation.getCreatedAt(),
        generation.getUpdatedAt());
  }

  private static GenerationRevisionResponse toRevisionResponse(GenerationRevision revision) {
    return new GenerationRevisionResponse(
        revision.getId(),
        revision.getGeneration().getId(),
        revision.getRevisionNumber(),
        revision.getRequirement(),
        revision.getCreatedAt());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
