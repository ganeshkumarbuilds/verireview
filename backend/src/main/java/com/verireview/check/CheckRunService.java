package com.verireview.check;

import com.verireview.audit.AuditService;
import com.verireview.check.dto.DashboardSummaryResponse;
import com.verireview.check.dto.ProjectSummary;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.FixRequestStatus;
import com.verireview.generation.Generation;
import com.verireview.generation.GenerationRepository;
import com.verireview.generation.GenerationStatus;
import com.verireview.project.Project;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static java.util.stream.Collectors.toList;

/**
 * Service for managing check runs across all four features.
 * Handles creation, status updates, and querying of check runs and steps.
 */
@Service
public class CheckRunService {

  private static final Logger log = LoggerFactory.getLogger(CheckRunService.class);

  private final CheckRunRepository checkRuns;
  private final CheckStepRepository checkSteps;
  private final CheckRunGateRepository checkRunGates;
  private final ProjectHealthScoreRepository healthScores;
  private final ProjectRepository projects;
  private final ProjectFileRepository files;
  private final FixRequestRepository fixRequests;
  private final ReviewRepository reviews;
  private final GenerationRepository generations;
  private final AuditService audits;
  private final CheckRunSseService sseService;

  public CheckRunService(
      CheckRunRepository checkRuns,
      CheckStepRepository checkSteps,
      CheckRunGateRepository checkRunGates,
      ProjectHealthScoreRepository healthScores,
      ProjectRepository projects,
      ProjectFileRepository files,
      FixRequestRepository fixRequests,
      ReviewRepository reviews,
      GenerationRepository generations,
      AuditService audits,
      CheckRunSseService sseService) {
    this.checkRuns = checkRuns;
    this.checkSteps = checkSteps;
    this.checkRunGates = checkRunGates;
    this.healthScores = healthScores;
    this.projects = projects;
    this.files = files;
    this.fixRequests = fixRequests;
    this.reviews = reviews;
    this.generations = generations;
    this.audits = audits;
    this.sseService = sseService;
  }

  /**
   * Creates a new check run for a project and feature.
   * Called at the start of GENERATE, REVIEW, FIX, or VERIFY execution.
   */
  @Transactional
  public CheckRun createCheckRun(UUID ownerId, UUID projectId, CheckFeature feature,
      UUID generationId, List<CheckStepDefinition> steps) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

    CheckRun run = new CheckRun(project, feature);
    if (generationId != null) {
      Generation gen = generations.findById(generationId).orElseThrow();
      if (!gen.getProject().getId().equals(projectId)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generation does not belong to project");
      }
      run.setGeneration(gen);
    }
    run.setStatus(CheckStatus.QUEUED);
    run.setProgress(0);
    run = checkRuns.save(run);

    // Create steps
    for (CheckStepDefinition stepDef : steps) {
      CheckStep step = new CheckStep(run, stepDef.stepOrder(), stepDef.name());
      checkSteps.save(step);
    }

    audits.record(project.getOwner(), "CHECK_RUN_CREATED", "check_run",
        run.getId().toString() + ":" + feature.name());

    return run;
  }

  /**
   * Marks a check run as RUNNING and records the start time.
   */
  @Transactional
  public void markRunning(UUID runId) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    run.setStatus(CheckStatus.RUNNING);
    run.setStartedAt(Instant.now());
    run.setProgress(0);
    checkRuns.save(run);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  /**
   * Updates a check run's progress and current step.
   */
  @Transactional
  public void updateProgress(UUID runId, int progress, String currentStep) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    run.setProgress(Math.max(0, Math.min(100, progress)));
    run.setCurrentStep(currentStep);
    checkRuns.save(run);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  /**
   * Marks a check run as SUCCEEDED.
   */
  @Transactional
  public void markSucceeded(UUID runId, String message) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    Instant finished = Instant.now();
    run.setStatus(CheckStatus.SUCCEEDED);
    run.setFinishedAt(finished);
    run.setProgress(100);
    if (run.getStartedAt() != null) {
      run.setDurationMs(Duration.between(run.getStartedAt(), finished).toMillis());
    }
    run.setErrorMessage(message);
    checkRuns.save(run);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  /**
   * Marks a check run as FAILED.
   */
  @Transactional
  public void markFailed(UUID runId, String error) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    Instant finished = Instant.now();
    run.setStatus(CheckStatus.FAILED);
    run.setFinishedAt(finished);
    if (run.getStartedAt() != null) {
      run.setDurationMs(Duration.between(run.getStartedAt(), finished).toMillis());
    }
    run.setErrorMessage(error);
    checkRuns.save(run);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  /**
   * Marks a check run as SKIPPED with a reason.
   */
  @Transactional
  public void markSkipped(UUID runId, String reason) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    run.setStatus(CheckStatus.SKIPPED);
    run.setFinishedAt(Instant.now());
    run.setErrorMessage(reason);
    checkRuns.save(run);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  /**
   * Updates a step's status and progress.
   */
  @Transactional
  public void updateStep(UUID runId, int stepOrder, CheckStatus status, int progress,
      String message, String logTail) {
    CheckStep step = checkSteps.findByCheckRunIdAndStepOrder(runId, stepOrder)
        .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepOrder));
    Instant now = Instant.now();
    step.setStatus(status);
    step.setProgress(Math.max(0, Math.min(100, progress)));
    step.setCurrentMessage(message);
    if (status == CheckStatus.RUNNING && step.getStartedAt() == null) {
      step.setStartedAt(now);
    }
    if (status == CheckStatus.SUCCEEDED || status == CheckStatus.FAILED || status == CheckStatus.SKIPPED) {
      step.setFinishedAt(now);
      if (step.getStartedAt() != null) {
        step.setDurationMs(Duration.between(step.getStartedAt(), now).toMillis());
      }
    }
    if (logTail != null) {
      step.setLogTail(truncate(logTail, 10000));
    }
    checkSteps.save(step);

    // Update run progress based on step progress
    updateRunProgressFromSteps(runId);

    // Notify SSE
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  private void updateRunProgressFromSteps(UUID runId) {
    List<CheckStep> steps = checkSteps.findByCheckRunIdOrderByStepOrderAsc(runId);
    if (steps.isEmpty()) return;
    int totalProgress = steps.stream().mapToInt(CheckStep::getProgress).sum();
    int avgProgress = totalProgress / steps.size();
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    run.setProgress(avgProgress);
    checkRuns.save(run);
  }

  /**
   * Records a gate result for a VERIFY check run.
   */
  @Transactional
  public void recordGate(UUID runId, String gateName, String gateDescription,
      boolean passed, String evidence, String details) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    if (run.getFeature() != CheckFeature.VERIFY) {
      throw new IllegalArgumentException("Gates can only be recorded for VERIFY runs");
    }
    CheckRunGate gate = new CheckRunGate(run, gateName, gateDescription);
    gate.setPassed(passed);
    gate.setEvidence(evidence);
    gate.setDetails(details);
    checkRunGates.save(gate);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  /**
   * Updates severity counts on a check run.
   */
  @Transactional
  public void updateSeverityCounts(UUID runId, int critical, int high, int medium, int low, int info) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    run.setSeverityCritical(critical);
    run.setSeverityHigh(high);
    run.setSeverityMedium(medium);
    run.setSeverityLow(low);
    run.setSeverityInfo(info);
    checkRuns.save(run);
    sseService.broadcastCheckRunUpdate(run.getProject().getId(), run);
  }

  @Transactional(readOnly = true)
  public CheckRun getCheckRun(UUID ownerId, UUID runId) {
    CheckRun run = checkRuns.findById(runId).orElseThrow();
    if (!run.getProject().getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Check run not found");
    }
    return run;
  }

  @Transactional(readOnly = true)
  public Page<CheckRun> listCheckRuns(UUID ownerId, UUID projectId, Pageable pageable) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    return checkRuns.findByProjectId(project.getId(), pageable);
  }

  @Transactional(readOnly = true)
  public List<CheckRun> getLatestCheckRuns(UUID ownerId, UUID projectId) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    return List.of(
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project.getId(), CheckFeature.GENERATE).orElse(null),
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project.getId(), CheckFeature.REVIEW).orElse(null),
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project.getId(), CheckFeature.FIX).orElse(null),
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project.getId(), CheckFeature.VERIFY).orElse(null)
    ).stream().filter(java.util.Objects::nonNull).toList();
  }

  @Transactional(readOnly = true)
  public List<CheckStep> getSteps(UUID ownerId, UUID runId) {
    CheckRun run = getCheckRun(ownerId, runId);
    return checkSteps.findByCheckRunIdOrderByStepOrderAsc(run.getId());
  }

  @Transactional(readOnly = true)
  public List<CheckRunGate> getGates(UUID ownerId, UUID runId) {
    CheckRun run = getCheckRun(ownerId, runId);
    return checkRunGates.findByCheckRunIdOrderByCreatedAtAsc(run.getId());
  }

  @Transactional(readOnly = true)
  public Optional<ProjectHealthScore> getLatestHealthScore(UUID ownerId, UUID projectId) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    return healthScores.findFirstByProjectIdOrderByLastComputedAtDesc(project.getId());
  }

  @Transactional(readOnly = true)
  public List<ProjectHealthScore> getHealthScoreHistory(UUID ownerId, UUID projectId, int limit) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    List<ProjectHealthScore> scores = healthScores.findByProjectIdOrderByLastComputedAtDesc(project.getId());
    return scores.stream().limit(limit).toList();
  }

  /**
   * Reconciles stale RUNNING check runs on startup.
   * Marks them as FAILED with a restart reason.
   */
  @Transactional
  public void reconcileStaleRuns() {
    Instant threshold = Instant.now().minus(Duration.ofMinutes(5));
    List<CheckRun> stale = checkRuns.findStaleRunning(threshold);
    if (!stale.isEmpty()) {
      log.warn("Found {} stale RUNNING check runs, marking as FAILED", stale.size());
      for (CheckRun run : stale) {
        run.setStatus(CheckStatus.FAILED);
        run.setErrorMessage("Check run interrupted by application restart");
        run.setFinishedAt(Instant.now());
        if (run.getStartedAt() != null) {
          run.setDurationMs(Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        }
        // Also mark any RUNNING steps as FAILED
        for (CheckStep step : checkSteps.findByCheckRunIdOrderByStepOrderAsc(run.getId())) {
          if (step.getStatus() == CheckStatus.RUNNING) {
            step.setStatus(CheckStatus.FAILED);
            step.setErrorMessage("Interrupted by restart");
            step.setFinishedAt(Instant.now());
            if (step.getStartedAt() != null) {
              step.setDurationMs(Duration.between(step.getStartedAt(), step.getFinishedAt()).toMillis());
            }
            checkSteps.save(step);
          }
        }
      }
      checkRuns.saveAll(stale);
    }
  }

  /**
   * Records a new health score for a project.
   */
  @Transactional
  public ProjectHealthScore recordHealthScore(Project project, int generateScore, int reviewScore,
      int fixScore, int verifyScore, int openCriticalHigh, int openFindingsTotal,
      Double fixedVerifiedRatio) {
    int totalScore = (generateScore + reviewScore + fixScore + verifyScore) / 4;
    ProjectHealthScore score = new ProjectHealthScore(project);
    score.setScore(totalScore);
    score.setGenerateScore(generateScore);
    score.setReviewScore(reviewScore);
    score.setFixScore(fixScore);
    score.setVerifyScore(verifyScore);
    score.setOpenCriticalHigh(openCriticalHigh);
    score.setOpenFindingsTotal(openFindingsTotal);
    score.setFixedVerifiedRatio(fixedVerifiedRatio);
    ProjectHealthScore saved = healthScores.save(score);
    sseService.broadcastDashboardUpdate(project.getOwner().getId());
    return saved;
  }

  private String truncate(String s, int max) {
    if (s == null) return null;
    return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
  }

  /**
   * Gets the dashboard summary for a user.
   */
  @Transactional(readOnly = true)
  public DashboardSummaryResponse getDashboardSummary(UUID ownerId) {
    List<Project> userProjects = projects.findByOwnerIdAndDeletedAtIsNull(ownerId);
    
    // Per-project rollup first, then derive the dashboard totals from it so
    // every number stays owner-scoped (no cross-account leakage).
    List<ProjectSummary> projectSummaries = userProjects.stream()
        .map(this::buildProjectSummary)
        .collect(toList());

    long totalFilesVerified = projectSummaries.stream()
        .mapToLong(ProjectSummary::filesVerified)
        .sum();

    long totalIssuesFound = projectSummaries.stream()
        .mapToLong(ProjectSummary::issuesFound)
        .sum();

    long totalIssuesFixed = projectSummaries.stream()
        .mapToLong(ProjectSummary::issuesFixed)
        .sum();

    return new DashboardSummaryResponse(
        totalFilesVerified,
        totalIssuesFound,
        totalIssuesFixed,
        projectSummaries);
  }

  private ProjectSummary buildProjectSummary(Project project) {
    // "Verified" files are the ingested files of a project that has actually
    // been through a completed REVIEW run (legacy reviews table) or a
    // SUCCEEDED REVIEW/VERIFY check run (unified model). A project that has
    // only been uploaded has nothing verified yet, so it reports 0 — never a
    // made-up count (AGENTS.md rule 11).
    boolean reviewed = reviews.existsByProjectIdAndStatusIn(
        project.getId(), List.of(ReviewStatus.COMPLETED));
    boolean verifiedRun = checkRuns.existsByProjectIdAndFeatureInAndStatus(
        project.getId(), List.of(CheckFeature.REVIEW, CheckFeature.VERIFY), CheckStatus.SUCCEEDED);
    long filesVerified =
        (reviewed || verifiedRun) ? files.countByProjectId(project.getId()) : 0;

    // Count open findings for this project
    long issuesFound = checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.CRITICAL)
        + checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.HIGH)
        + checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.MEDIUM)
        + checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.LOW)
        + checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.INFO);
    
    // Count fixed issues for this project (FixRequest COMPLETED via Finding -> Review -> Project)
    long issuesFixed = fixRequests.countByFindingReviewProjectIdAndStatus(project.getId(), FixRequestStatus.COMPLETED);
    
    // Last activity is the newest of the latest review, latest check run and
    // the project row itself so the column stays meaningful for projects that
    // only have reviews or were just touched.
    Instant lastReviewAt = reviews.findFirstByProjectIdOrderByCreatedAtDesc(project.getId())
        .map(Review::getCreatedAt)
        .orElse(null);
    Instant lastRunAt = checkRuns.findFirstByProjectIdOrderByCreatedAtDesc(project.getId())
        .map(CheckRun::getCreatedAt)
        .orElse(null);
    Instant lastActivityAt = latest(lastRunAt, latest(lastReviewAt, project.getUpdatedAt()));

    return new ProjectSummary(
        project.getId(),
        project.getName(),
        filesVerified,
        issuesFound,
        issuesFixed,
        lastActivityAt);
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }

  public record CheckStepDefinition(int stepOrder, String name) {
  }
}