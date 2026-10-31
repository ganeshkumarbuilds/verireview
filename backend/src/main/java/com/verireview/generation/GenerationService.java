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
import com.verireview.generation.dto.GenerationAnswerRequest;
import com.verireview.generation.dto.GenerationFileView;
import com.verireview.generation.dto.GenerationFixPreviewResponse;
import com.verireview.generation.dto.GenerationQuestionsRequest;
import com.verireview.generation.dto.GenerationResponse;
import com.verireview.generation.dto.GenerationRevisionResponse;
import com.verireview.generation.dto.PlanPreviewRequest;
import com.verireview.generation.dto.PlanPreviewResponse;
import com.verireview.generation.dto.StartGenerationRequest;
import com.verireview.generation.dto.UpdateGenerationRequest;
import com.verireview.fix.FixRequest;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.FixRequestStatus;
import com.verireview.fix.Patch;
import com.verireview.fix.PatchRepository;
import com.verireview.fix.PatchStatus;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.FindingStatus;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.user.UserRepository;
import com.verireview.verification.VerificationRun;
import com.verireview.verification.VerificationRunRepository;
import com.verireview.verification.VerificationVerdict;
import com.verireview.verification.BuildStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>The database password is optional: a blank password is accepted and
 * passed to the run as an empty string.
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
  private final FixRequestRepository fixRequests;
  private final UserRepository users;
  private final ProjectRepository projects;
  private final ProjectFileRepository projectFiles;
  private final GenerationRunner runner;
  private final AuditService audits;
  private final ObjectMapper objects;
  private final PatchRepository patches;
  private final ProjectStorage storage;
  private final GenerationWizardService wizard;
  private final GenerationEventStream eventStream;
  private final GenerationFixApprovalRepository fixApprovals;
  private final GenerationFileVersionRepository fileVersions;
  private final ConcurrentMap<UUID, GenerationSecrets> secrets = new ConcurrentHashMap<>();

  private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

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
      FixRequestRepository fixRequests,
      PatchRepository patches,
      UserRepository users,
      ProjectRepository projects,
      ProjectFileRepository projectFiles,
      @Lazy GenerationRunner runner,
      AuditService audits,
      ObjectMapper objects,
      ProjectStorage storage,
      GenerationWizardService wizard,
      GenerationEventStream eventStream,
      GenerationFixApprovalRepository fixApprovals,
      GenerationFileVersionRepository fileVersions) {
    this.generations = generations;
    this.revisions = revisions;
    this.artifacts = artifacts;
    this.plans = plans;
    this.executionEvidence = executionEvidence;
    this.executions = executions;
    this.verificationRuns = verificationRuns;
    this.reviews = reviews;
    this.findings = findings;
    this.fixRequests = fixRequests;
    this.patches = patches;
    this.users = users;
    this.projects = projects;
    this.projectFiles = projectFiles;
    this.runner = runner;
    this.audits = audits;
    this.objects = objects;
    this.storage = storage;
    this.wizard = wizard;
    this.eventStream = eventStream;
    this.fixApprovals = fixApprovals;
    this.fileVersions = fileVersions;
  }

  public GenerationExecutionEvidenceRepository executionEvidence() {
    return executionEvidence;
  }

  public ReviewRepository reviews() {
    return reviews;
  }

  public PatchRepository patches() {
    return patches;
  }

  @Transactional(readOnly = true)
  public Patch getPatch(UUID patchId) {
    return patches.findById(patchId).orElseThrow();
  }

  @Transactional
  public GenerationResponse create(UUID ownerId, CreateGenerationRequest request) {
    String name = request.name().trim();
    if (generations.existsByOwnerIdAndName(ownerId, name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation name is already used");
    }
    boolean draft = Boolean.TRUE.equals(request.draft());
    String requirement = request.requirement().trim();
    GenerationQuestionsRequest wizardProject = new GenerationQuestionsRequest(
        name,
        requirement,
        request.description(),
        request.backend(),
        request.frontend(),
        request.languageRuntime(),
        request.backendCustom(),
        request.frontendCustom());
    boolean hasAnswers = request.answers() != null && !request.answers().isEmpty();
    GenerationWizardCatalog.Resolution resolved = hasAnswers
        ? wizard.resolve(wizardProject, request.answers())
        : null;
    if (resolved != null && !resolved.complete()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Answer these questions before generating: " + String.join(", ", resolved.missing()));
    }
    if (resolved != null) {
      checkAnswersMatchAiConfig(resolved, request.aiConfig());
    }
    GenerationDatabase database = resolved != null ? resolved.database() : request.database();
    GenerationAuth auth = resolved != null
        ? resolved.auth()
        : (request.auth() == null ? GenerationAuth.NONE : request.auth());
    List<GenerationIntegration> integrations = resolved != null
        ? resolved.integrations()
        : normalizeIntegrations(request.integrations());
    boolean databaseConfigureLater = resolved != null
        ? resolved.databaseConfigureLater()
        : Boolean.TRUE.equals(request.databaseConfigureLater());
    if (databaseConfigureLater && database != GenerationDatabase.NONE) {
      // "Configure later" is explicit; no connection metadata is fabricated.
      database = GenerationDatabase.NONE;
    }
    String answersJson = wizard.storedAnswersJson(wizardProject, request.answers());
    checkSelection(request.backend(), request.frontend(), database, auth, integrations);
    checkStackCoherence(request.backend(), request.frontend(), database,
        request.databaseConfig() != null,
        request.aiConfig().provider(), request.aiConfig().baseUrl(), request.aiConfig().apiKey(),
        request.aiConfig().model(), draft, databaseConfigureLater);
    if (!draft) {
      requireSecrets(request, databaseConfigureLater);
    }

    Generation generation = new Generation();
    generation.setOwner(users.getReferenceById(ownerId));
    generation.setName(name);
    generation.setRequirement(requirement);
    generation.setDescription(blankToNull(request.description()));
    generation.setBackendStack(request.backend());
    generation.setFrontendStack(request.frontend());
    generation.setLanguageRuntime(blankToNull(request.languageRuntime()));
    generation.setBackendCustom(request.backend() == GenerationBackend.OTHER
        ? blankToNull(request.backendCustom()) : null);
    generation.setFrontendCustom(request.frontend() == GenerationFrontend.OTHER
        ? blankToNull(request.frontendCustom()) : null);
    generation.setAnswersJson(answersJson);
    generation.setDatabaseConfigureLater(databaseConfigureLater);
    generation.setDatabaseType(database);
    generation.setAuthType(auth);
    generation.setIntegrations(joinIntegrations(integrations));
    if (database != GenerationDatabase.NONE && !databaseConfigureLater) {
      CreateGenerationRequest.DatabaseConfig db = request.databaseConfig();
      generation.setDbHost(db.host().trim());
      generation.setDbPort(db.port());
      generation.setDbName(db.name().trim());
      generation.setDbUsername(db.username().trim());
      generation.setDbSslMode(blankToNull(db.sslMode()));
    }
    generation.setAiProvider(request.aiConfig().provider());
    String model = request.aiConfig().model() == null ? "" : request.aiConfig().model().trim();
    generation.setAiModel(model.isEmpty() && request.aiConfig().provider() == GenerationAiProvider.NONE
        ? "template" : model);
    generation.setAiBaseUrl(blankToNull(request.aiConfig().baseUrl()));
    generation.setStatus(draft ? GenerationStatus.DRAFT : GenerationStatus.QUEUED);
    try {
      generations.saveAndFlush(generation);
      revisions.save(new GenerationRevision(
          generation, 1, generation.getRequirement()));
      if (request.approvedPlan() != null) {
        validateApprovedPlan(request.approvedPlan());
        savePlan(generation.getId(), 1, toAgentPlan(generation.getId(), request.approvedPlan()));
      }
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation name is already used");
    }

    if (!draft) {
      // In-memory only: never persisted, never logged, never returned.
      // The database password is optional: a blank value becomes "".
      String dbPassword = database != GenerationDatabase.NONE && !databaseConfigureLater
          ? (request.databaseConfig().password() == null
              ? "" : request.databaseConfig().password())
          : null;
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
    if (wantsDb && password == null) {
      password = "";
    }
    if (generation.getAiProvider() != GenerationAiProvider.NONE
        && (apiKey == null || apiKey.isBlank())) {
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
    if (request.languageRuntime() != null) {
      if (request.languageRuntime().length() > 200) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid language/runtime");
      }
      generation.setLanguageRuntime(blankToNull(request.languageRuntime()));
    }
    if (request.backendCustom() != null) {
      if (request.backendCustom().length() > 200) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid custom backend");
      }
      generation.setBackendCustom(blankToNull(request.backendCustom()));
    }
    if (request.frontendCustom() != null) {
      if (request.frontendCustom().length() > 200) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid custom frontend");
      }
      generation.setFrontendCustom(blankToNull(request.frontendCustom()));
    }
    GenerationDatabase database = generation.getDatabaseType();
    if (request.database() != null) {
      database = request.database();
      generation.setDatabaseType(database);
    }
    GenerationAuth auth = generation.getAuthType() == null
        ? GenerationAuth.NONE : generation.getAuthType();
    if (request.auth() != null) {
      auth = request.auth();
      generation.setAuthType(auth);
    }
    List<GenerationIntegration> integrations = request.integrations() == null
        ? parseIntegrations(generation.getIntegrations())
        : normalizeIntegrations(request.integrations());
    generation.setIntegrations(joinIntegrations(integrations));
    checkSelection(generation.getBackendStack(), generation.getFrontendStack(), database,
        auth, integrations);
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

  /**
   * Cross-field selection rules. These are user-facing choices, so incoherent
   * combinations are rejected with a 400 before anything is persisted.
   */
  private void checkSelection(GenerationBackend backend, GenerationFrontend frontend,
      GenerationDatabase database, GenerationAuth auth,
      List<GenerationIntegration> integrations) {
    if (backend == GenerationBackend.NONE && frontend == GenerationFrontend.NONE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose at least a backend or a frontend");
    }
    if (backend == GenerationBackend.NONE
        && (auth == GenerationAuth.JWT || auth == GenerationAuth.OAUTH_GOOGLE)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Authentication requires a backend");
    }
    if (integrations.contains(GenerationIntegration.STRIPE)
        && integrations.contains(GenerationIntegration.RAZORPAY)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose either Stripe or Razorpay, not both");
    }
  }

  /**
   * The wizard answers are authoritative. Reject a request whose AI config
   * names a different engine/model than the user approved instead of silently
   * running one and displaying the other.
   */
  private static void checkAnswersMatchAiConfig(
      GenerationWizardCatalog.Resolution resolved, CreateGenerationRequest.AiConfig aiConfig) {
    String engine = resolved.values().get(GenerationWizardCatalog.ENGINE);
    GenerationAiProvider expectedProvider = switch (engine == null ? "" : engine) {
      case "TEMPLATE" -> GenerationAiProvider.NONE;
      case "CUSTOM" -> GenerationAiProvider.CUSTOM;
      default -> GenerationAiProvider.OPENROUTER;
    };
    if (aiConfig.provider() != expectedProvider) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "AI provider does not match the approved generation engine");
    }
    if (!"TEMPLATE".equals(engine)) {
      String expectedModel = resolved.values().getOrDefault(GenerationWizardCatalog.MODEL, "");
      String requestedModel = aiConfig.model() == null ? "" : aiConfig.model().trim();
      if (!expectedModel.equals(requestedModel)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "AI model does not match the approved generation model");
      }
    }
    if ("CUSTOM".equals(engine)) {
      String expectedBaseUrl = resolved.values().getOrDefault(GenerationWizardCatalog.BASE_URL, "");
      String requestedBaseUrl = aiConfig.baseUrl() == null ? "" : aiConfig.baseUrl().trim();
      if (!expectedBaseUrl.equals(requestedBaseUrl)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "AI base URL does not match the approved generation endpoint");
      }
    }
  }

  private void checkStackCoherence(
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database,
      boolean hasDatabaseConfig,
      GenerationAiProvider provider, String baseUrl, String apiKey, String model, boolean draft,
      boolean databaseConfigureLater) {
    boolean wantsDb = database != GenerationDatabase.NONE;
    if (wantsDb && !hasDatabaseConfig) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database configuration is required for the selected database");
    }
    if (!wantsDb && hasDatabaseConfig) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Database configuration must be omitted when no database is selected");
    }
    if (databaseConfigureLater && wantsDb) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose None when configuring the database later");
    }
    if (provider == GenerationAiProvider.NONE) {
      if (apiKey != null && !apiKey.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "API key must be omitted when provider is NONE");
      }
      if (baseUrl != null && !baseUrl.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Base URL must be omitted when provider is NONE");
      }
      if (backend != GenerationBackend.JAVA_SPRING_BOOT
          || (frontend != GenerationFrontend.REACT_TYPESCRIPT
              && frontend != GenerationFrontend.NONE)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Provider NONE currently supports Java Spring Boot templates with React or no frontend");
      }
      return;
    }
    if (model == null || model.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "AI model is required");
    }
    if (provider == GenerationAiProvider.CUSTOM && (baseUrl == null || baseUrl.isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Base URL is required for a custom provider");
    }
    if (!draft && (apiKey == null || apiKey.isBlank())) {
      // Drafts may omit secrets (re-supplied at start); dispatched runs may not.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "AI API key is required");
    }
  }

  private static List<GenerationIntegration> normalizeIntegrations(
      List<GenerationIntegration> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return List.copyOf(new LinkedHashSet<>(values));
  }

  private static String joinIntegrations(List<GenerationIntegration> values) {
    return values.stream().map(Enum::name)
        .collect(java.util.stream.Collectors.joining(","));
  }

  static List<GenerationIntegration> parseIntegrations(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<GenerationIntegration> result = new ArrayList<>();
    for (String part : value.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        result.add(GenerationIntegration.valueOf(part.trim()));
      } catch (IllegalArgumentException ignored) {
        // Unknown legacy value: ignore instead of breaking status reads.
      }
    }
    return List.copyOf(result);
  }

  private void requireSecrets(CreateGenerationRequest request, boolean databaseConfigureLater) {
    // The database password is optional: a blank password is passed through.
    // When the user explicitly chose "configure later", no DB secret exists.
    if (request.aiConfig().provider() != GenerationAiProvider.NONE
        && (request.aiConfig().apiKey() == null || request.aiConfig().apiKey().isBlank())) {
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

  /**
   * Step 4 plan preview. Derives the stack-aware scaffold only; no row is
   * created and no AI call is made, so the user can review and re-plan freely.
   */
  @Transactional(readOnly = true)
  public PlanPreviewResponse previewPlan(UUID ownerId, PlanPreviewRequest request) {
    GenerationAuth auth = request.auth() == null ? GenerationAuth.NONE : request.auth();
    List<GenerationIntegration> integrations = normalizeIntegrations(request.integrations());
    checkSelection(request.backend(), request.frontend(), request.database(), auth, integrations);
    return GenerationScaffoldPreview.build(request.backend(), request.frontend(),
        request.database(), auth, integrations);
  }

  private void validateApprovedPlan(PlanPreviewResponse plan) {
    Set<String> paths = new HashSet<>();
    for (PlanPreviewResponse.PlanFile file : plan.files()) {
      String path = file.path();
      if (path == null || path.isBlank() || path.startsWith("/")
          || path.startsWith("\\") || path.contains("\\")
          || path.matches("^[A-Za-z]:.*")
          || !path.matches("^[A-Za-z0-9._@+/-]+$")
          || java.util.Arrays.stream(path.split("/", -1))
              .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
          || !paths.add(path)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "The approved plan contains an invalid or duplicate file path");
      }
    }
    Set<String> directories = new HashSet<>();
    for (String directory : plan.directories()) {
      if (directory == null || directory.isBlank() || directory.startsWith("/")
          || directory.startsWith("\\") || directory.contains("\\")
          || directory.matches("^[A-Za-z]:.*")
          || !directory.matches("^[A-Za-z0-9._@+/-]+$")
          || java.util.Arrays.stream(directory.split("/", -1))
              .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
          || !directories.add(directory)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "The approved plan contains an invalid or duplicate directory");
      }
    }
  }

  private AiGenerationPlanResult toAgentPlan(UUID generationId, PlanPreviewResponse plan) {
    return new AiGenerationPlanResult(
        generationId.toString(),
        plan.files().stream()
            .map(file -> new AiGenerationPlanResult.PlannedFile(file.path(), file.purpose()))
            .toList(),
        plan.notes(),
        new AiGenerationPlanResult.PlanSections(
            plan.architecture(),
            plan.dependencies(),
            plan.directories(),
            plan.apis(),
            plan.steps()));
  }

  @Transactional(readOnly = true)
  public AiGenerationPlanResult savedPlan(UUID generationId, int iteration) {
    GenerationPlan plan = plans.findByGenerationIdAndIteration(generationId, iteration).orElse(null);
    if (plan == null) {
      return null;
    }
    try {
      JsonNode root = objects.readTree(plan.getPlanJson());
      List<AiGenerationPlanResult.PlannedFile> files = new ArrayList<>();
      JsonNode fileNodes = root.get("files");
      if (fileNodes != null && fileNodes.isArray()) {
        for (JsonNode file : fileNodes) {
          files.add(new AiGenerationPlanResult.PlannedFile(
              textOrEmpty(file.get("path")), textOrEmpty(file.get("purpose"))));
        }
      }

      return new AiGenerationPlanResult(
          generationId.toString(),
          List.copyOf(files),
          textOrEmpty(root.get("notes")),
          new AiGenerationPlanResult.PlanSections(
              textOrEmpty(root.get("architecture")),
              strings(root.get("dependencies")),
              strings(root.get("directories")),
              textOrEmpty(root.get("apis")),
              strings(root.get("steps"))));
    } catch (Exception e) {
      throw new IllegalStateException("Could not read the approved generation plan", e);
    }
  }

  @Transactional(readOnly = true)
  public UUID planId(UUID generationId, int iteration) {
    return plans.findByGenerationIdAndIteration(generationId, iteration)
        .map(GenerationPlan::getId)
        .orElseThrow(() -> new IllegalStateException("Approved generation plan is missing"));
  }

  /**
   * Generated workspace files for the browse view. Owner-scoped and read from
   * the latest artifact's workspace; server paths never leave the backend.
   */
  @Transactional(readOnly = true)
  public List<GenerationFileView> listFiles(UUID ownerId, UUID generationId) {
    owned(ownerId, generationId);
    GenerationArtifact artifact = artifacts
        .findFirstByGenerationIdOrderByCreatedAtDesc(generationId)
        .orElse(null);
    if (artifact == null) {
      return List.of();
    }
    Path workspace = storage.generationWorkspaceDir(generationId, artifact.getIteration());
    if (!Files.isDirectory(workspace)) {
      return List.of();
    }
    List<GenerationFileView> result = new ArrayList<>();
    try (var stream = Files.walk(workspace)) {
      stream.filter(Files::isRegularFile)
          .filter(path -> !Files.isSymbolicLink(path))
          .forEach(path -> {
            try {
              result.add(new GenerationFileView(
                  workspace.relativize(path).toString().replace('\\', '/'),
                  Files.size(path)));
            } catch (IOException ignored) {
              // A file that disappeared mid-scan is simply not listed.
            }
          });
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Could not read the generated workspace");
    }
    result.sort(java.util.Comparator.comparing(GenerationFileView::path));
    return List.copyOf(result);
  }

  /**
   * Reads one generated text file for the browse view. Owner-scoped, jailed
   * to the iteration workspace, capped, and never interprets content as HTML.
   */
  @Transactional(readOnly = true)
  public com.verireview.generation.dto.GenerationFileContentView readFile(
      UUID ownerId, UUID generationId, String path) {
    owned(ownerId, generationId);
    if (path == null || path.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File path is required");
    }
    GenerationArtifact artifact = artifacts
        .findFirstByGenerationIdOrderByCreatedAtDesc(generationId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "There is no generated workspace yet"));
    Path workspace = storage.generationWorkspaceDir(generationId, artifact.getIteration());
    Path file = storage.resolveJailed(workspace, path);
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
    }
    final byte[] bytes;
    try (var in = Files.newInputStream(file)) {
      bytes = in.readNBytes((int) ProjectStorage.FILE_VIEW_BYTES + 1);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not read the file");
    }
    boolean truncated = bytes.length > ProjectStorage.FILE_VIEW_BYTES;
    int length = Math.min(bytes.length, (int) ProjectStorage.FILE_VIEW_BYTES);
    for (int i = 0; i < length; i++) {
      if (bytes[i] == 0) {
        return new com.verireview.generation.dto.GenerationFileContentView(
            path, null, false, true);
      }
    }
    String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
    return new com.verireview.generation.dto.GenerationFileContentView(
        path, content, truncated, false);
  }

  /**
   * Materializes the reviewed workspace as a normal {@link Project} so the
   * Review workflow can analyze it. The generated files are copied into the
   * project directory (never moved), and the generation is linked to the new
   * project. Idempotent: calling again returns the already-linked generation.
   */
  @Transactional
  public GenerationResponse sendToReview(UUID ownerId, UUID generationId) {
    Generation generation = owned(ownerId, generationId);
    if (generation.getProject() != null) {
      return toResponse(generation);
    }
    if (generation.getStatus() != GenerationStatus.REVIEWED
        && generation.getStatus() != GenerationStatus.COMPLETED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Wait for the generation to finish reviewing first");
    }
    GenerationArtifact artifact = artifacts
        .findFirstByGenerationIdOrderByCreatedAtDesc(generationId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "There is no generated workspace to send"));
    Path workspace = storage.generationWorkspaceDir(generationId, artifact.getIteration());
    if (!Files.isDirectory(workspace)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The generated workspace is no longer available");
    }

    Project project = new Project(users.getReferenceById(ownerId),
        uniqueProjectName(ownerId, generation.getName()), ProjectSourceType.GENERATED);
    project.setDescription(generation.getDescription());
    project.setLanguage(generation.getLanguageRuntime() != null
        ? generation.getLanguageRuntime()
        : GenerationRunner.primaryLanguage(generation.getBackendStack()));
    projects.saveAndFlush(project);
    Path projectDir = storage.projectDir(project.getId());
    try {
      Files.createDirectories(projectDir);
      List<ProjectFile> rows = new ArrayList<>();
      try (var stream = Files.walk(workspace)) {
        for (Path path : (Iterable<Path>) stream::iterator) {
          if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            continue;
          }
          String relative = workspace.relativize(path).toString().replace('\\', '/');
          Path target = storage.resolveJailed(projectDir, relative);
          Files.createDirectories(target.getParent());
          byte[] bytes = Files.readAllBytes(path);
          Files.write(target, bytes);
          ProjectFile row = new ProjectFile(project, relative, bytes.length, sha256(bytes));
          row.setLanguage(languageOf(relative));
          row.setContentRef(relative);
          rows.add(row);
        }
      }
      projectFiles.saveAll(rows);
      project.setStorageRef(projectDir.toAbsolutePath().normalize().toString());
      projects.save(project);
    } catch (Exception e) {
      storage.deleteQuietly(projectDir);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Could not prepare the generated project for review", e);
    }

    generation.setProject(project);
    generations.save(generation);
    audits.record(generation.getOwner(), "GENERATION_SENT_TO_REVIEW", "generation",
        generationId.toString());
    return toResponse(generation);
  }

  private String uniqueProjectName(UUID ownerId, String base) {
    String root = base == null || base.isBlank() ? "generated-project" : base.strip();
    if (root.length() > 190) {
      root = root.substring(0, 190);
    }
    String candidate = root;
    int suffix = 2;
    while (projects.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, candidate)) {
      String tail = " (" + suffix++ + ")";
      candidate = (root.length() + tail.length() > 200
          ? root.substring(0, 200 - tail.length())
          : root) + tail;
    }
    return candidate;
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
        generation.getLanguageRuntime(),
        generation.getBackendCustom(),
        generation.getFrontendCustom(),
        generation.getDatabaseType(),
        generation.getAuthType(),
        parseIntegrations(generation.getIntegrations()),
        generation.getDbHost(),
        generation.getDbPort(),
        generation.getDbName(),
        generation.getDbUsername(),
        generation.getDbSslMode(),
        generation.getAiProvider(),
        generation.getAiModel(),
        generation.getAiBaseUrl(),
        answerValues(generation.getAnswersJson()));
  }

  /** Non-secret question values for the agents; secret entries have no value. */
  private Map<String, String> answerValues(String json) {
    Map<String, String> values = new LinkedHashMap<>();
    try {
      JsonNode root = objects.readTree(json == null ? "[]" : json);
      if (root != null && root.isArray()) {
        for (JsonNode item : root) {
          JsonNode id = item.get("questionId");
          JsonNode value = item.get("value");
          if (id != null && id.isTextual() && value != null && value.isTextual()) {
            values.put(id.asText(), value.asText());
          }
        }
      }
    } catch (Exception ignored) {
      // Legacy or malformed rows simply contribute no answer context.
    }
    return Map.copyOf(values);
  }

  public record GenerationSnapshot(
      UUID id,
      UUID ownerId,
      String name,
      String requirement,
      String description,
      GenerationBackend backend,
      GenerationFrontend frontend,
      String languageRuntime,
      String backendCustom,
      String frontendCustom,
      GenerationDatabase database,
      GenerationAuth auth,
      List<GenerationIntegration> integrations,
      String dbHost,
      Integer dbPort,
      String dbName,
      String dbUsername,
      String dbSslMode,
      GenerationAiProvider aiProvider,
      String aiModel,
      String aiBaseUrl,
      Map<String, String> answers) {
  }

  /** Returns the run secrets without consuming them (for rebuild flows). */
  GenerationSecrets peekSecrets(UUID generationId) {
    return secrets.get(generationId);
  }

  /** Consumes and discards the run secrets (single delivery). */
  GenerationSecrets takeSecrets(UUID generationId) {
    return secrets.remove(generationId);
  }

  /** Clears secrets when generation reaches terminal state. */
  void clearSecrets(UUID generationId) {
    secrets.remove(generationId);
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

    @Transactional
    public GenerationResponse approveFixes(
        UUID ownerId, UUID generationId, List<UUID> requestedFindingIds, String apiKey) {
      Generation managed = owned(ownerId, generationId);
      if (managed.getStatus() != GenerationStatus.AWAITING_FIX_APPROVAL) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Fix approval is available only after review has findings");
      }
      if (managed.getIteration() >= managed.getMaxIterations()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "The five-round fix limit has been reached. Review the remaining findings or revert.");
      }

      List<Review> generationReviews =
          reviews.findByGenerationIdOrderByCreatedAtDesc(generationId);
      if (generationReviews.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "No completed review is available");
      }
      List<Finding> unresolved = findings.findByReviewId(generationReviews.get(0).getId()).stream()
          .filter(this::isUnresolved)
          .toList();
      if (unresolved.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "There are no unresolved findings");
      }

      Set<UUID> validIds = unresolved.stream().map(Finding::getId)
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      Set<UUID> selectedIds = requestedFindingIds == null || requestedFindingIds.isEmpty()
          ? validIds
          : new LinkedHashSet<>(requestedFindingIds);
      if (selectedIds.isEmpty()
          || (requestedFindingIds != null && !requestedFindingIds.isEmpty()
              && selectedIds.size() != requestedFindingIds.size())
          || !validIds.containsAll(selectedIds)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Choose only unresolved findings from the latest review");
      }
      List<Finding> approvedFindings = unresolved.stream()
          .filter(finding -> selectedIds.contains(finding.getId()))
          .filter(finding -> !fixRequests.findByFindingIdAndRequestedByIdAndStatusIn(
              finding.getId(), ownerId,
              List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS)).isEmpty())
          .toList();
      if (approvedFindings.size() != selectedIds.size()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Each selected finding requires an approved fix request for this user");
      }

      GenerationSnapshot snapshot = snapshot(generationId);
      boolean requiresKey = snapshot.aiProvider() != GenerationAiProvider.NONE;
      if (requiresKey && (apiKey == null || apiKey.isBlank())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Enter the AI provider key again to run this fix round");
      }
      if (!requiresKey) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "This template-only generation has no coding provider configured for automatic fixes");
      }

      int round = managed.getIteration() + 1;
      GenerationFixApproval approval = new GenerationFixApproval(
          managed,
          users.getReferenceById(ownerId),
          round,
          writeJson(selectedIds),
          unresolved.size(),
          java.time.Instant.now());
      fixApprovals.saveAndFlush(approval);
      managed.setIteration(round);
      managed.setStatus(GenerationStatus.FIXING);
      managed.setError(null);
      secrets.put(generationId, new GenerationSecrets(null, apiKey));
      audits.record(managed.getOwner(), "GENERATION_FIX_APPROVED", "generation", generationId.toString());
      dispatchFixAfterCommit(generationId, approval.getId());
      publishAfterCommit(generationId);
      return toResponse(managed);
    }

    @Transactional(readOnly = true)
    public GenerationFixPreviewResponse previewFixFiles(
        UUID ownerId, UUID generationId, List<UUID> requestedFindingIds) {
      Generation generation = owned(ownerId, generationId);
      if (generation.getStatus() != GenerationStatus.AWAITING_FIX_APPROVAL) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Fix scope is available only after review has findings");
      }
      Review latest = reviews.findByGenerationIdOrderByCreatedAtDesc(generationId).stream()
          .findFirst()
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No review is available"));
      List<Finding> unresolved = findings.findByReviewId(latest.getId()).stream()
          .filter(this::isUnresolved)
          .toList();
      Set<UUID> validIds = unresolved.stream().map(Finding::getId)
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      Set<UUID> selectedIds = requestedFindingIds == null || requestedFindingIds.isEmpty()
          ? validIds : new LinkedHashSet<>(requestedFindingIds);
      if (selectedIds.isEmpty()
          || (requestedFindingIds != null && !requestedFindingIds.isEmpty()
              && selectedIds.size() != requestedFindingIds.size())
          || !validIds.containsAll(selectedIds)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Choose only unresolved findings from the latest review");
      }
      List<Finding> selected = unresolved.stream()
          .filter(finding -> selectedIds.contains(finding.getId()))
          .toList();
      Path workspace = storage.generationWorkspaceDir(generationId, generation.getIteration());
      Map<String, String> files = new LinkedHashMap<>();
      try (var paths = Files.walk(workspace)) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          if (Files.isSymbolicLink(path) || Files.size(path) > GenerationRunner.MAX_CHARS_PER_FILE) {
            continue;
          }
          String relative = workspace.relativize(path).toString().replace('\\', '/');
          String content = Files.readString(path, StandardCharsets.UTF_8);
          if (content.indexOf('\0') < 0) {
            files.put(relative, content);
          }
        }
      } catch (IOException e) {
        throw new IllegalStateException("Could not inspect generated files for the fix preview", e);
      }
      List<String> affected = GenerationFixScope.affectedFiles(selected, files).stream().sorted().toList();
      if (affected.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "No generated files match the selected findings");
      }
      return new GenerationFixPreviewResponse(affected, selected.size());
    }

    private void dispatchFixAfterCommit(UUID generationId, UUID approvalId) {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            runner.runApprovedFix(generationId, approvalId);
          }
        });
      } else {
        runner.runApprovedFix(generationId, approvalId);
      }
    }

    @Transactional(readOnly = true)
    public FixRunContext fixRunContext(UUID generationId, UUID approvalId) {
      GenerationFixApproval approval = fixApprovals.findByIdAndGenerationIdAndUserId(
              approvalId, generationId,
              generations.findById(generationId).orElseThrow().getOwner().getId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
              "No approved fix request exists for this generation"));
      if (!"APPROVED".equals(approval.getStatus())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "The approved fix request is no longer available");
      }
      List<UUID> ids = readUuidList(approval.getSelectedFindingIds());
      List<Finding> selected = ids.stream()
          .map(id -> findings.findById(id).orElseThrow(() ->
              new ResponseStatusException(HttpStatus.CONFLICT, "An approved finding no longer exists")))
          .toList();
      return new FixRunContext(approval.getId(), approval.getRoundNumber(), selected);
    }

    public record FixRunContext(UUID approvalId, int roundNumber, List<Finding> findings) {
    }

    @Transactional
    public void beginFixApproval(UUID approvalId) {
      GenerationFixApproval approval = fixApprovals.findById(approvalId).orElseThrow();
      approval.setStatus("RUNNING");
      publishAfterCommit(approval.getGeneration().getId());
    }

    @Transactional
    public void setFixApprovalStatus(UUID approvalId, String status, Integer afterCount) {
      GenerationFixApproval approval = fixApprovals.findById(approvalId).orElseThrow();
      approval.setStatus(status);
      if (afterCount != null) {
        approval.setAfterFindingCount(afterCount);
      }
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        approval.setCompletedAt(java.time.Instant.now());
      }
      publishAfterCommit(approval.getGeneration().getId());
    }

    @Transactional(readOnly = true)
    public boolean hasApprovedFix(UUID ownerId, UUID generationId, UUID findingId) {
      return fixApprovals.findByGenerationIdOrderByRoundNumberAscCreatedAtAsc(generationId).stream()
          .anyMatch(approval -> approval.getUser().getId().equals(ownerId)
              && ("APPROVED".equals(approval.getStatus()) || "RUNNING".equals(approval.getStatus()))
              && readUuidList(approval.getSelectedFindingIds()).contains(findingId));
    }

    @Transactional
    public void finishReviewRound(UUID generationId, int roundNumber) {
      Generation generation = generations.findById(generationId).orElseThrow();
      int unresolved = unresolvedFindingCount(generationId);
      generation.setStatus(unresolved > 0
          ? GenerationStatus.AWAITING_FIX_APPROVAL
          : GenerationStatus.REVIEWED);
      generation.setError(null);
      fixApprovals.findByGenerationIdOrderByRoundNumberAscCreatedAtAsc(generationId).stream()
          .filter(approval -> approval.getRoundNumber() == roundNumber
              && "RUNNING".equals(approval.getStatus()))
          .findFirst()
          .ifPresent(approval -> {
            approval.setAfterFindingCount(unresolved);
            approval.setStatus("COMPLETED");
            approval.setCompletedAt(java.time.Instant.now());
          });
      secrets.remove(generationId);
      publishAfterCommit(generationId);
    }

    @Transactional(readOnly = true)
    public int unresolvedFindingCount(UUID generationId) {
      return reviews.findByGenerationIdOrderByCreatedAtDesc(generationId).stream()
          .findFirst()
          .map(review -> (int) findings.findByReviewId(review.getId()).stream()
              .filter(this::isUnresolved)
              .count())
          .orElse(0);
    }

    @Transactional
    public GenerationResponse retryFailedStage(
        UUID ownerId, UUID generationId, UUID executionId, String apiKey) {
      Generation generation = owned(ownerId, generationId);
      AgentExecution execution = executions.findById(executionId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent stage not found"));
      if (execution.getGeneration() == null
          || !execution.getGeneration().getId().equals(generationId)
          || execution.getStatus() != AgentExecutionStatus.FAILED) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a failed stage of this generation can be retried");
      }
      if (execution.getRoundNumber() != generation.getIteration()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a failed stage from the current round can be retried");
      }
      if (generation.getStatus() != GenerationStatus.FAILED) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "The generation is not in a failed state");
      }
      GenerationSnapshot snapshot = snapshot(generationId);
      boolean requiresKey = snapshot.aiProvider() != GenerationAiProvider.NONE
          && (execution.getAgentType() == AgentType.PLANNING
              || execution.getAgentType() == AgentType.CODING
              || execution.getAgentType() == AgentType.VERIFIED);
      if (requiresKey && (apiKey == null || apiKey.isBlank())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Enter the AI provider key again to retry this stage");
      }
      if (apiKey != null && !apiKey.isBlank()) {
        secrets.put(generationId, new GenerationSecrets(null, apiKey));
      }
      UUID currentApprovalId = fixApprovals
          .findByGenerationIdOrderByRoundNumberAscCreatedAtAsc(generationId).stream()
          .filter(approval -> approval.getRoundNumber() == generation.getIteration())
          .reduce((first, second) -> second)
          .map(GenerationFixApproval::getId)
          .orElse(null);
      if (currentApprovalId != null) {
        setFixApprovalStatus(currentApprovalId, "APPROVED", null);
      }
      generation.setStatus(switch (execution.getAgentType()) {
        case PLANNING -> GenerationStatus.PLANNING;
        case CODING -> currentApprovalId == null ? GenerationStatus.CODING : GenerationStatus.FIXING;
        case VERIFIED -> GenerationStatus.VERIFYING;
        case REVIEW -> GenerationStatus.REVIEWING;
        case RATING -> GenerationStatus.REVIEWED;
      });
      generation.setError(null);
      runner.retryFailedStage(generationId, execution.getAgentType(), currentApprovalId);
      publishAfterCommit(generationId);
      return toResponse(generation);
    }

    @Transactional(readOnly = true)
    public RetryVerificationContext retryVerificationContext(UUID generationId, int round) {
      GenerationExecutionEvidence evidence = executionEvidence
          .findByGenerationIdAndIteration(generationId, round)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
              "Build evidence is not available for verification retry"));
      VerificationRun verification = verificationRuns.findByGenerationIdOrderByCreatedAtDesc(generationId)
          .stream()
          .filter(item -> item.getGeneration() != null
              && item.getGeneration().getId().equals(generationId))
          .findFirst()
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
              "Verification record is not available for retry"));
      return new RetryVerificationContext(evidence, verification.getId());
    }

    public record RetryVerificationContext(GenerationExecutionEvidence evidence, UUID verificationRunId) {
    }

    @Transactional
    public void saveFixFileVersions(
        UUID generationId, UUID approvalId, int round,
        Map<String, String> before, Map<String, String> after) {
      Generation generation = generations.findById(generationId).orElseThrow();
      GenerationFixApproval approval = fixApprovals.findById(approvalId).orElseThrow();
      if (!approval.getGeneration().getId().equals(generationId)
          || approval.getRoundNumber() != round) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Fix approval does not match this round");
      }
      if (!before.keySet().equals(after.keySet())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Fix output did not match approved files");
      }
      before.forEach((path, prior) -> fileVersions.save(new GenerationFileVersion(
          generation, approval, round, path, prior, after.get(path))));
    }

    @Transactional
    public GenerationResponse revertFixRound(UUID ownerId, UUID generationId, int round) {
      Generation generation = owned(ownerId, generationId);
      if (generation.getStatus() != GenerationStatus.AWAITING_FIX_APPROVAL
          && generation.getStatus() != GenerationStatus.REVIEWED
          && generation.getStatus() != GenerationStatus.FAILED) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Files can be reverted only when the generation is not running");
      }
      List<GenerationFileVersion> versions =
          fileVersions.findByGenerationIdAndRoundNumberOrderByFilePathAsc(generationId, round);
      if (versions.isEmpty() || versions.stream().allMatch(GenerationFileVersion::isReverted)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "No restorable file versions exist for this round");
      }
      Path workspace = storage.generationWorkspaceDir(generationId, generation.getIteration());
      try {
        for (GenerationFileVersion version : versions) {
          Path target = storage.resolveJailed(workspace, version.getFilePath());
          Files.createDirectories(target.getParent());
          Files.writeString(target, version.getBeforeContent(), StandardCharsets.UTF_8);
          version.setReverted(true);
        }
      } catch (IOException e) {
        throw new IllegalStateException("Could not restore the prior generated files", e);
      }
      GenerationFixApproval approval = versions.get(0).getApproval();
      approval.setStatus("REVERTED");
      generation.setStatus(GenerationStatus.REVIEWING);
      generation.setError(null);
      audits.record(generation.getOwner(), "GENERATION_FIX_REVERTED", "generation",
          generationId + ":round:" + round);
      publishAfterCommit(generationId);
      int currentRound = generation.getIteration();
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            runner.rereviewAfterRevert(generationId, currentRound);
          }
        });
      } else {
        runner.rereviewAfterRevert(generationId, currentRound);
      }
      return toResponse(generation);
    }

    private boolean isUnresolved(Finding finding) {
      return finding.getStatus() == FindingStatus.OPEN
          || finding.getStatus() == FindingStatus.FIX_REQUESTED
          || finding.getStatus() == FindingStatus.FIX_PROPOSED;
    }

    private String writeJson(Set<UUID> values) {
      try {
        return objects.writeValueAsString(values);
      } catch (Exception e) {
        throw new IllegalStateException("Could not encode approved findings", e);
      }
    }

    private List<UUID> readUuidList(String value) {
      try {
        return objects.readValue(value,
            objects.getTypeFactory().constructCollectionType(List.class, UUID.class));
      } catch (Exception e) {
        throw new IllegalStateException("Could not read approved finding identifiers", e);
      }
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
    execution.setRoundNumber(managed.getIteration());
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setInputHash(inputHash);
    UUID executionId = executions.save(execution).getId();
    publishAfterCommit(generationId);
    return executionId;
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
    if (execution.getGeneration() != null) {
      publishAfterCommit(execution.getGeneration().getId());
    }
  }

  @Transactional(readOnly = true)
  public AgentExecution getAgentExecution(UUID executionId) {
    return executions.findById(executionId).orElseThrow();
  }

  @Transactional(readOnly = true)
  public Review getReview(UUID reviewId) {
    return reviews.findById(reviewId).orElseThrow();
  }

  @Transactional(readOnly = true)
  public GenerationResponse.RatingView ratingForGeneration(UUID generationId) {
    return reviews.findByGenerationIdOrderByCreatedAtDesc(generationId).stream()
        .findFirst()
        .map(review -> GenerationRatingCalculator.rate(findings.findByReviewId(review.getId())))
        .orElseGet(() -> GenerationRatingCalculator.rate(List.of()));
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
   * Creates a generation-owned verification run for an iteration.
   *
   * <p>The current {@code verification_runs} schema requires exactly one
   * owner: {@code generation_id} or {@code patch_id}. Generation rebuild and
   * re-verification runs therefore remain generation-owned. The applied patch
   * is preserved by its own Patch/FixRequest records.
   *
   * @return the persisted verification run id
   */
  @Transactional
  public UUID createVerificationRun(UUID generationId, int iteration,
      UUID executionEvidenceId) {
    Generation managed = generations.findById(generationId).orElseThrow();
    GenerationExecutionEvidence evidence =
        executionEvidence.findById(executionEvidenceId).orElseThrow();

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

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String languageOf(String path) {
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".java")) return "java";
    if (lower.endsWith(".py")) return "python";
    if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
    if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
    if (lower.endsWith(".vue")) return "vue";
    if (lower.endsWith(".sql")) return "sql";
    if (lower.endsWith(".md")) return "markdown";
    return null;
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

  @Transactional
  public int persistGenerationStaticFindings(
      UUID reviewId, List<GenerationStaticAnalyzer.StaticFinding> staticFindings) {
    Review review = reviews.findById(reviewId).orElseThrow();
    List<Finding> rows = new ArrayList<>();
    for (GenerationStaticAnalyzer.StaticFinding item : staticFindings) {
      FindingCategory category = switch (item.category()) {
        case "security" -> FindingCategory.SECURITY;
        case "style" -> FindingCategory.STYLE;
        default -> FindingCategory.BUG;
      };
      String dedupKey = Fingerprint.of(
          "generation-static", item.category(), item.file(), item.line(), item.title());
      if (findings.existsByReviewIdAndDedupKey(reviewId, dedupKey)) {
        continue;
      }
      Finding finding = new Finding(
          review, category, item.severity(), FindingSource.DETERMINISTIC, item.title());
      finding.setFilePath(item.file());
      finding.setLineStart(item.line());
      finding.setLineEnd(item.line());
      finding.setRule("generation-static:" + item.category());
      finding.setAnalyzer("generation-static");
      finding.setExplanation(item.explanation());
      finding.setDedupKey(dedupKey);
      finding.setToolConfirmed(true);
      rows.add(finding);
    }
    findings.saveAll(rows);
    return rows.size();
  }

  /**
   * Computes the dedup keys the Review Agent reports in a fresh scan,
   * without persisting anything. Used after a rebuild's re-review to tell
   * which previously flagged findings no longer reproduce. Mirrors the same
   * validation and key derivation as {@link #persistGenerationReviewFindings}
   * so a key computed here always matches the one stored on a Finding row.
   */
  public Set<String> computeReportedDedupKeys(AiReviewResult result) {
    Set<String> keys = new LinkedHashSet<>();
    for (AiProposedFinding item : result.findings()) {
      if (!"AI".equalsIgnoreCase(item.source()) && !"VERIFIED".equalsIgnoreCase(item.source())) {
        continue;
      }
      FindingCategory category;
      FindingSeverity severity;
      try {
        category = FindingCategory.valueOf(item.category().trim().toUpperCase());
        severity = FindingSeverity.valueOf(item.severity().trim().toUpperCase());
      } catch (Exception e) {
        continue;
      }
      if (item.title() == null || item.title().isBlank()) {
        continue;
      }
      String title = trim(item.title(), 500);
      String filePath = trim(item.filePath(), 1000);
      Integer lineStart = nonNegative(item.lineStart());
      keys.add(Fingerprint.of("review-agent", title, filePath, lineStart, item.description()));
    }
    return keys;
  }

  /**
   * Closes the loop a rebuild+re-review starts. Any Finding in this review
   * that has an active fix request (FIX_REQUESTED or FIX_PROPOSED) and whose
   * dedup key does NOT appear in the fresh scan is verified fixed — the
   * rebuild and re-review themselves are the verification, not an AI claim.
   * Its open FixRequest(s) move to COMPLETED. A Finding whose key still
   * appears in the fresh scan is left untouched for another fix round.
   *
   * @return number of findings marked VERIFIED_FIXED
   */
  @Transactional
  public int resolveFixedFindings(UUID reviewId, Set<String> currentDedupKeys) {
    Review review = reviews.findById(reviewId).orElseThrow();
    Set<UUID> approvedFindingIds = Set.of();
    if (review.getGeneration() != null) {
      Generation generation = review.getGeneration();
      approvedFindingIds = fixApprovals
          .findByGenerationIdOrderByRoundNumberAscCreatedAtAsc(generation.getId()).stream()
          .filter(approval -> approval.getRoundNumber() == generation.getIteration()
              && "RUNNING".equals(approval.getStatus()))
          .reduce((first, second) -> second)
          .map(approval -> Set.copyOf(readUuidList(approval.getSelectedFindingIds())))
          .orElse(Set.of());
    }
    Set<UUID> selectedIds = approvedFindingIds;
    List<Finding> candidates = findings.findByReviewId(reviewId).stream()
        .filter(f -> f.getStatus() == FindingStatus.FIX_REQUESTED
            || f.getStatus() == FindingStatus.FIX_PROPOSED
            || (f.getStatus() == FindingStatus.OPEN && selectedIds.contains(f.getId())))
        .toList();
    int resolved = 0;
    for (Finding finding : candidates) {
      if (finding.getDedupKey() != null && currentDedupKeys.contains(finding.getDedupKey())) {
        continue; // still reproduces in the fresh scan — not fixed
      }
      finding.setStatus(FindingStatus.VERIFIED_FIXED);
      resolved++;
      for (FixRequest fr : fixRequests.findByFindingIdAndStatusIn(
          finding.getId(), List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS))) {
        fr.setStatus(FixRequestStatus.COMPLETED);
      }
    }
    return resolved;
  }

  /**
   * Creates a FixRequest for a generation finding. This is the approval gate
   * before the Coding Agent can propose a patch. The FixRequest is linked to
   * the generation via the finding's review.
   *
   * @return the persisted FixRequest id
   */
  @Transactional
  public UUID createGenerationFixRequest(UUID generationId, UUID findingId, UUID ownerId, String scopeNote) {
    Generation managed = generations.findById(generationId).orElseThrow();
    if (!managed.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation not found");
    }
    Finding finding = findings.findById(findingId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found"));
    // Verify finding belongs to this generation's review
    if (finding.getReview() == null || finding.getReview().getGeneration() == null
        || !finding.getReview().getGeneration().getId().equals(generationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found for this generation");
    }
    // Check if open fix request already exists
    if (fixRequests.existsByFindingIdAndStatusIn(finding.getId(),
        List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An open fix request already exists for this finding");
    }
    FixRequest fixRequest = new FixRequest(finding, users.getReferenceById(ownerId));
    if (scopeNote != null && !scopeNote.isBlank()) {
      fixRequest.setScopeNote(scopeNote.trim());
    }
    try {
      fixRequests.saveAndFlush(fixRequest);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An open fix request already exists for this finding");
    }
    audits.record(managed.getOwner(), "GENERATION_FIX_REQUEST_CREATED", "fix_request",
        fixRequest.getId().toString());
    return fixRequest.getId();
  }

  /**
   * Creates FixRequests for every OPEN finding of the generation's latest
   * review. This is the user-approved entry point to the generation fix loop:
   * it records approval only and never mutates source code. Findings that
   * already carry an open request are skipped and reported, never duplicated.
   * Only REVIEWED generations qualify — rebuilding starts from REVIEWED.
   *
   * @return review id, created FixRequest ids, and skipped-already-open count
   */
  @Transactional
  public BulkFixRequestResult createGenerationFixRequests(UUID ownerId, UUID generationId,
      String scopeNote) {
    Generation managed = owned(ownerId, generationId);
    if (managed.getStatus() != GenerationStatus.REVIEWED
        && managed.getStatus() != GenerationStatus.AWAITING_FIX_APPROVAL) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Fix requests can only be created after a completed review");
    }
    List<Review> generationReviews =
        reviews.findByGenerationIdOrderByCreatedAtDesc(managed.getId());
    if (generationReviews.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "No review available for this generation");
    }
    Review latest = generationReviews.get(0);
    List<UUID> created = new ArrayList<>();
    int skippedOpen = 0;
    for (Finding finding : findings.findByReviewId(latest.getId())) {
      if (finding.getStatus() != FindingStatus.OPEN) {
        continue;
      }
      if (fixRequests.existsByFindingIdAndStatusIn(finding.getId(),
          List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS))) {
        skippedOpen++;
        continue;
      }
      FixRequest fixRequest = new FixRequest(finding, users.getReferenceById(ownerId));
      if (scopeNote != null && !scopeNote.isBlank()) {
        fixRequest.setScopeNote(scopeNote.trim());
      }
      try {
        fixRequests.saveAndFlush(fixRequest);
        created.add(fixRequest.getId());
      } catch (DataIntegrityViolationException e) {
        skippedOpen++;
      }
    }
    audits.record(managed.getOwner(), "GENERATION_FIX_REQUESTS_CREATED", "generation",
        managed.getId().toString());
    return new BulkFixRequestResult(latest.getId(), List.copyOf(created), skippedOpen);
  }

  public record BulkFixRequestResult(UUID reviewId, List<UUID> created, int skippedOpen) {
  }

  /**
   * Proposes a patch for a generation FixRequest using the GenerationCodingAgent.
   * The Coding Agent reads from the generation workspace (read-only).
   *
   * @return the persisted Patch id
   */
  @Transactional
  public UUID proposeGenerationPatch(UUID generationId, UUID fixRequestId, UUID ownerId) {
    Generation managed = generations.findById(generationId).orElseThrow();
    if (!managed.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation not found");
    }
    FixRequest fixRequest = fixRequests.findById(fixRequestId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found"));
    // Verify FixRequest belongs to this generation
    if (fixRequest.getFinding().getReview().getGeneration() == null
        || !fixRequest.getFinding().getReview().getGeneration().getId().equals(generationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found for this generation");
    }
    if (fixRequest.getStatus() != FixRequestStatus.REQUESTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "FixRequest must be in REQUESTED state to propose a patch");
    }

    // This will be called from PatchService which uses GenerationCodingAgent
    // Here we just validate and prepare; the actual AI call happens in PatchService
    return fixRequestId;
  }

  /**
   * Triggers the rebuild and re-verification flow for a generation after a patch
   * has been applied. This dispatches the async runner to execute the REBUILDING
   * and REVERIFYING phases against the modified generation workspace.
   *
   * @param generationId the generation to rebuild
   * @param ownerId the owner of the generation (for authorization)
   * @param patchId the patch that was applied and triggered this rebuild
   * @return the execution evidence id for the rebuild
   */
  @Transactional
  public UUID triggerRebuildAndReverify(UUID generationId, UUID ownerId, UUID patchId) {
    Generation managed = generations.findById(generationId).orElseThrow();
    if (!managed.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation not found");
    }
    // Verify the patch belongs to this generation
    Patch patch = patches.findById(patchId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found"));
    if (patch.getGeneration() == null || !patch.getGeneration().getId().equals(generationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found for this generation");
    }
    if (!hasApprovedFix(ownerId, generationId, patch.getFixRequest().getFinding().getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A user-approved generation fix request is required before rebuilding");
    }
    if (patch.getStatus() != PatchStatus.APPLIED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Patch must be in APPLIED state to trigger rebuild");
    }
    // REVIEWED is the expected entry point for the patch-triggered rebuild.
    // The state is changed before the async dispatch so clients immediately
    // observe that the rebuild has started.
    if (!managed.getStatus().canTransitionTo(GenerationStatus.REBUILDING)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Generation cannot transition to REBUILDING from " + managed.getStatus());
    }
    managed.setStatus(GenerationStatus.REBUILDING);
    audits.record(managed.getOwner(), "GENERATION_REBUILD_TRIGGERED", "generation",
        generationId.toString());
    dispatchRebuildAfterCommit(generationId, patchId);
    return patchId; // Return patchId as reference for the async runner
  }

  private void dispatchRebuildAfterCommit(UUID generationId, UUID patchId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          runner.triggerRebuildAndReverify(generationId, patchId);
        }
      });
    } else {
      runner.triggerRebuildAndReverify(generationId, patchId);
    }
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
    publishAfterCommit(generationId);
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

  private void publishAfterCommit(UUID generationId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          eventStream.publish(generationId);
        }
      });
    } else {
      eventStream.publish(generationId);
    }
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
      List<GenerationResponse.PlanFileView> files = new ArrayList<>();
      JsonNode items = root.get("files");
      if (items != null && items.isArray()) {
        for (JsonNode item : items) {
          files.add(new GenerationResponse.PlanFileView(
              textOrEmpty(item.get("path")),
              textOrEmpty(item.get("purpose"))));
        }
      }
      return new GenerationResponse.PlanView(
          plan.getIteration(),
          plan.getFileCount(),
          textOrEmpty(root.get("architecture")),
          strings(root.get("dependencies")),
          strings(root.get("directories")),
          textOrEmpty(root.get("apis")),
          strings(root.get("steps")),
          List.copyOf(files));
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

  private GenerationResponse toResponse(Generation generation, int revisionNumber,
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
          sealed && generation.getDbHost() != null);
    }
    GenerationResponse.AiView ai = new GenerationResponse.AiView(
        generation.getAiProvider(),
        generation.getAiModel(),
        generation.getAiBaseUrl(),
        sealed && generation.getAiProvider() != GenerationAiProvider.NONE);
    GenerationResponse.ArtifactView artifactView = artifact == null ? null
        : new GenerationResponse.ArtifactView(
            artifact.getId(),
            artifact.getIteration(),
            artifact.getFileCount(),
            artifact.getTotalChars(),
            artifact.getSha256(),
            artifact.getCreatedAt());

    // Workflow stats from findings for this generation
    GenerationResponse.WorkflowStatsView workflowStats = computeWorkflowStats(generation);

    // Latest verification for current iteration
    GenerationResponse.VerificationView verification = findLatestVerification(generation);

    // Latest review for current iteration
    GenerationResponse.ReviewView review = findLatestReview(generation);

    List<GenerationResponse.FindingView> generationFindings = findGenerationFindings(generation);

    // Latest sandbox evidence (build/test actually ran or was skipped)
    GenerationResponse.ExecutionView execution = findLatestExecution(generation);

    // Agent workflow timeline
    List<GenerationResponse.AgentStepView> agentWorkflow = findAgentWorkflow(generation);
    List<GenerationResponse.FixRoundView> fixRounds = fixApprovals
        .findByGenerationIdOrderByRoundNumberAscCreatedAtAsc(generation.getId()).stream()
        .map(approval -> new GenerationResponse.FixRoundView(
            approval.getId(),
            approval.getRoundNumber(),
            readUuidList(approval.getSelectedFindingIds()),
            approval.getBeforeFindingCount(),
            approval.getAfterFindingCount(),
            approval.getStatus(),
            approval.getApprovedAt(),
            approval.getCompletedAt()))
        .toList();
    List<GenerationResponse.FileVersionView> versions = fileVersions
        .findByGenerationIdOrderByRoundNumberAscFilePathAsc(generation.getId()).stream()
        .map(version -> new GenerationResponse.FileVersionView(
            version.getId(),
            version.getRoundNumber(),
            version.getFilePath(),
            version.getBeforeContent(),
            version.getAfterContent(),
            version.isReverted(),
            version.getCreatedAt()))
        .toList();

    // Rating is available only after a persisted Rating Agent execution completes.
    GenerationResponse.RatingView rating = findLatestRating(generation, agentWorkflow);

    // Backend-authoritative download gate
    boolean downloadReady = computeDownloadReady(generation, workflowStats, verification, review, artifactView);

    // Preview is available only after the same backend quality gate as the ZIP.
    // Do not infer readiness from enum ordinal ordering.
    boolean previewReady = downloadReady;

    return new GenerationResponse(
        generation.getId(),
        generation.getName(),
        generation.getRequirement(),
        generation.getDescription(),
        generation.getBackendStack(),
        generation.getFrontendStack(),
        generation.getDatabaseType(),
        generation.getAuthType() == null ? GenerationAuth.NONE : generation.getAuthType(),
        parseIntegrations(generation.getIntegrations()),
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
        workflowStats,
        verification,
        review,
        rating,
        generationFindings,
        execution,
        agentWorkflow,
        fixRounds,
        versions,
        verificationLevel(artifactView, verification, review, execution, hasUnresolvedFindings(generation)),
        downloadReady,
        previewReady,
        generation.getLanguageRuntime(),
        generation.getBackendCustom(),
        generation.getFrontendCustom(),
        generation.isDatabaseConfigureLater(),
        answerViews(generation.getAnswersJson()),
        generation.getCreatedAt(),
        generation.getUpdatedAt());
  }

  private List<GenerationResponse.AnswerView> answerViews(String json) {
    List<GenerationResponse.AnswerView> answers = new ArrayList<>();
    try {
      JsonNode root = objects.readTree(json == null ? "[]" : json);
      if (root != null && root.isArray()) {
        for (JsonNode item : root) {
          answers.add(new GenerationResponse.AnswerView(
              textOrEmpty(item.get("questionId")),
              textOrEmpty(item.get("prompt")),
              textOrEmpty(item.get("type")),
              item.get("value") == null || item.get("value").isNull()
                  ? null : textOrEmpty(item.get("value")),
              item.path("skipped").asBoolean(false),
              item.path("required").asBoolean(false),
              item.path("secret").asBoolean(false),
              item.path("configured").asBoolean(false)));
        }
      }
    } catch (Exception ignored) {
      // Polling must not fail because a legacy row has no answers.
    }
    return List.copyOf(answers);
  }

  private GenerationResponse.WorkflowStatsView computeWorkflowStats(Generation generation) {
    List<Review> generationReviews =
        reviews.findByGenerationIdOrderByCreatedAtDesc(generation.getId());
    if (generationReviews.isEmpty()) {
      return new GenerationResponse.WorkflowStatsView(0, 0, 0, 0, 0, 0);
    }

    Review latestReview = generationReviews.get(0);
    List<Finding> allFindings = findings.findByReviewId(latestReview.getId());

    int total = allFindings.size();
    int open = 0;
    int fixed = 0;
    int bugs = 0;
    int issues = 0;
    int errors = 0;

    for (Finding finding : allFindings) {
      // Only VERIFIED_FIXED is counted as actually fixed.
      if (finding.getStatus() == FindingStatus.VERIFIED_FIXED) {
        fixed++;
      } else {
        open++;
      }

      if (finding.getCategory() == FindingCategory.BUG) {
        bugs++;
      } else if (finding.getCategory() == FindingCategory.CODE_QUALITY) {
        issues++;
      } else if (finding.getCategory() == FindingCategory.SECURITY) {
        errors++;
      }
    }

    return new GenerationResponse.WorkflowStatsView(
        total, open, fixed, bugs, issues, errors);
  }

  private GenerationResponse.VerificationView findLatestVerification(Generation generation) {
    List<VerificationRun> runs = verificationRuns.findByGenerationIdOrderByCreatedAtDesc(generation.getId());
    if (runs.isEmpty()) {
      return null;
    }
    VerificationRun run = runs.get(0);
    return new GenerationResponse.VerificationView(
        run.getVerdict() != null ? run.getVerdict().name() : "PENDING",
        run.getBuildStatus() != null ? run.getBuildStatus().name() : "PENDING",
        run.getTestsTotal(),
        run.getTestsPassed(),
        run.getTestsFailed(),
        run.getTestsSkipped(),
        run.getDurationMs(),
        run.getLogRef());
  }

  private GenerationResponse.ReviewView findLatestReview(Generation generation) {
    List<Review> generationReviews = reviews.findByGenerationIdOrderByCreatedAtDesc(generation.getId());
    if (generationReviews.isEmpty()) {
      return null;
    }
    Review latestReview = generationReviews.get(0);
    return new GenerationResponse.ReviewView(
        latestReview.getStatus() != null ? latestReview.getStatus().name() : "QUEUED",
        latestReview.getFindingCount(),
        latestReview.getError());
  }

  private GenerationResponse.ExecutionView findLatestExecution(Generation generation) {
    return executionEvidence.findByGenerationIdOrderByCreatedAtDesc(generation.getId()).stream()
        .findFirst()
        .map(evidence -> new GenerationResponse.ExecutionView(
            evidence.getCommand(),
            evidence.getExitCode(),
            evidence.getBuildStatus() != null ? evidence.getBuildStatus().name() : "PENDING",
            evidence.getTestStatus() != null ? evidence.getTestStatus().name() : "PENDING",
            evidence.getFailureReason(),
            evidence.getDurationMs(),
            evidence.getBuildStatus() == GenerationExecutionEvidence.BuildStatus.SKIPPED,
            evidence.getCreatedAt()))
        .orElse(null);
  }

  private List<GenerationResponse.AgentStepView> findAgentWorkflow(Generation generation) {
    List<AgentExecution> executionsList = executions.findByGenerationIdOrderByCreatedAtAsc(generation.getId());
    List<GenerationResponse.AgentStepView> steps = new ArrayList<>();
    for (AgentExecution ex : executionsList) {
      steps.add(new GenerationResponse.AgentStepView(
          ex.getAgentType() != null ? ex.getAgentType().name() : "UNKNOWN",
          ex.getStatus() != null ? ex.getStatus().name() : "PENDING",
          ex.getDurationMs(),
          ex.getError(),
          ex.getCreatedAt(),
          ex.getStatus() == AgentExecutionStatus.RUNNING
              || ex.getStatus() == AgentExecutionStatus.PENDING
              ? null : ex.getUpdatedAt(),
          ex.getOutputRef(),
          ex.getRoundNumber()));
    }
    return steps;
  }

  private GenerationResponse.RatingView findLatestRating(
      Generation generation, List<GenerationResponse.AgentStepView> agentWorkflow) {
    boolean ratingCompleted = agentWorkflow.stream()
        .filter(step -> "RATING".equals(step.agentType()))
        .reduce((first, second) -> second)
        .map(step -> "COMPLETED".equals(step.status()))
        .orElse(false);
    return ratingCompleted ? ratingForGeneration(generation.getId()) : null;
  }

  private List<GenerationResponse.FindingView> findGenerationFindings(Generation generation) {
    return reviews.findByGenerationIdOrderByCreatedAtDesc(generation.getId()).stream()
        .findFirst()
        .map(review -> findings.findByReviewId(review.getId()).stream()
            .map(finding -> new GenerationResponse.FindingView(
                finding.getId(),
                finding.getFilePath(),
                finding.getLineStart(),
                GenerationRatingCalculator.category(finding),
                finding.getSeverity() == null ? "INFO" : finding.getSeverity().name(),
                finding.getTitle(),
                finding.getExplanation() == null || finding.getExplanation().isBlank()
                    ? finding.getDescription() : finding.getExplanation(),
                finding.getStatus() == null ? "OPEN" : finding.getStatus().name()))
            .toList())
        .orElseGet(List::of);
  }

  private boolean computeDownloadReady(Generation generation,
      GenerationResponse.WorkflowStatsView workflowStats,
      GenerationResponse.VerificationView verification,
      GenerationResponse.ReviewView review,
      GenerationResponse.ArtifactView artifact) {
    if (artifact == null) {
      return false;
    }
    if (generation.getStatus() != GenerationStatus.REVIEWED) {
      return false;
    }
    if (review == null || !"COMPLETED".equals(review.status())) {
      return false;
    }
    return !hasUnresolvedFindings(generation);
  }

  private String verificationLevel(
      GenerationResponse.ArtifactView artifact,
      GenerationResponse.VerificationView verification,
      GenerationResponse.ReviewView review,
      GenerationResponse.ExecutionView execution,
      boolean unresolvedFindings) {
    if (artifact == null || review == null || !"COMPLETED".equals(review.status())) {
      return "NOT_RUN";
    }
    if (unresolvedFindings) {
      return "ISSUES_FOUND";
    }
    if (execution != null && execution.notVerified()) {
      return "SANDBOX_UNAVAILABLE";
    }
    if (verification != null
        && "VERIFIED".equals(verification.verdict())
        && "SUCCESS".equals(verification.buildStatus())) {
      return "BUILD_AND_TESTS";
    }
    return "STATIC";
  }

  private boolean hasUnresolvedFindings(Generation generation) {
    List<Review> generationReviews =
        reviews.findByGenerationIdOrderByCreatedAtDesc(generation.getId());
    if (generationReviews.isEmpty()) {
      return false;
    }

    Review latestReview = generationReviews.get(0);

    // REQUESTED and PROPOSED are still unresolved; only VERIFIED_FIXED is resolved.
    for (FindingStatus status : List.of(
        FindingStatus.OPEN,
        FindingStatus.FIX_REQUESTED,
        FindingStatus.FIX_PROPOSED)) {
      if (findings.existsByReviewIdAndStatus(latestReview.getId(), status)) {
        return true;
      }
    }
    return false;
  }

  public record DownloadResult(boolean ready, String reason, String filename, byte[] content) {
  }

  @Transactional(readOnly = true)
  public DownloadResult prepareDownload(UUID ownerId, UUID generationId) {
    Generation generation = owned(ownerId, generationId);
    GenerationResponse.WorkflowStatsView workflowStats = computeWorkflowStats(generation);
    GenerationResponse.VerificationView verification = findLatestVerification(generation);
    GenerationResponse.ReviewView review = findLatestReview(generation);
    GenerationArtifact artifact = artifacts.findFirstByGenerationIdOrderByCreatedAtDesc(generation.getId()).orElse(null);
    GenerationResponse.ArtifactView artifactView = artifact == null ? null
        : new GenerationResponse.ArtifactView(
            artifact.getId(),
            artifact.getIteration(),
            artifact.getFileCount(),
            artifact.getTotalChars(),
            artifact.getSha256(),
            artifact.getCreatedAt());

    if (artifact == null) {
      return new DownloadResult(false, "No artifact available", null, null);
    }
    if (generation.getStatus() != GenerationStatus.REVIEWED) {
      return new DownloadResult(false, "Generation review has not completed", null, null);
    }
    if (review == null || !"COMPLETED".equals(review.status())) {
      return new DownloadResult(false, "Review not completed", null, null);
    }
    if (hasUnresolvedFindings(generation)) {
      return new DownloadResult(
          false,
          "All findings must be resolved and verified",
          null,
          null);
    }

    Path workspace = storage.generationWorkspaceDir(generation.getId(), artifact.getIteration());
    byte[] zipContent;
    String filename;
    try {
      zipContent = buildZip(workspace, generation);
      filename = generation.getName() + ".zip";
    } catch (Exception e) {
      log.warn("Could not build ZIP for generation {}: {}", generationId, e.toString());
      return new DownloadResult(false, "Could not build download artifact", null, null);
    }
    return new DownloadResult(true, null, filename, zipContent);
  }

  private byte[] buildZip(Path workspace, Generation generation) throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         ZipOutputStream zos = new ZipOutputStream(baos)) {
      List<String> manifestFiles = new ArrayList<>();
      try (var paths = Files.walk(workspace)) {
        for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
          String relativePath = workspace.relativize(file).toString().replace('\\', '/');
          if (".env.example".equals(relativePath)) {
            continue;
          }
          ZipEntry entry = new ZipEntry(relativePath);
          zos.putNextEntry(entry);
          Files.copy(file, zos);
          zos.closeEntry();
          manifestFiles.add(relativePath);
        }
      }

      String envExample = buildEnvExample(generation);
      ZipEntry envEntry = new ZipEntry(".env.example");
      zos.putNextEntry(envEntry);
      zos.write(envExample.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      manifestFiles.add(".env.example");

      manifestFiles.sort(String::compareTo);
      ZipEntry manifestEntry = new ZipEntry("manifest.json");
      zos.putNextEntry(manifestEntry);
      zos.write(manifestJson(manifestFiles).getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();

      zos.finish();
      return baos.toByteArray();
    }
  }

  private String manifestJson(List<String> files) {
    StringBuilder json = new StringBuilder("{\n  \"files\": [\n");
    for (int i = 0; i < files.size(); i++) {
      if (i > 0) {
        json.append(",\n");
      }
      json.append("    \"").append(files.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\"");
    }
    return json.append("\n  ]\n}\n").toString();
  }

  private String buildEnvExample(Generation generation) {
    StringBuilder sb = new StringBuilder();
    sb.append("# VeriReview generated project - environment configuration\n");
    sb.append("# Copy this file to .env and fill in your actual values\n");
    sb.append("# NEVER commit .env with real credentials to version control\n\n");
    sb.append("PORT=\n");

    if (generation.getDatabaseType() != GenerationDatabase.NONE) {
      sb.append("# Database configuration\n");
      sb.append("DATABASE_URL=\n");
      sb.append("DATABASE_HOST=\n");
      sb.append("DATABASE_PORT=\n");
      sb.append("DATABASE_NAME=\n");
      sb.append("DATABASE_USERNAME=\n");
      sb.append("DATABASE_PASSWORD=\n");
      sb.append("DATABASE_SSL_MODE=\n");
      sb.append("\n");
    }

    if (generation.getAiProvider() != GenerationAiProvider.NONE) {
      sb.append("# AI configuration\n");
      sb.append("AI_API_KEY=\n");
      sb.append("AI_PROVIDER=\n");
      sb.append("AI_MODEL=\n");
      sb.append("AI_BASE_URL=\n");
    }

    return sb.toString();
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