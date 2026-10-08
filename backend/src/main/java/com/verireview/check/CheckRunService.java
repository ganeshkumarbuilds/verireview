package com.verireview.check;

import com.verireview.audit.AuditService;
import com.verireview.check.dto.DashboardSummaryResponse;
import com.verireview.check.dto.FeatureSummary;
import com.verireview.check.dto.RunSummary;
import com.verireview.check.dto.SeveritySummary;
import com.verireview.check.dto.TrendPoint;
import com.verireview.generation.Generation;
import com.verireview.generation.GenerationRepository;
import com.verireview.generation.GenerationStatus;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
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
  private final GenerationRepository generations;
  private final AuditService audits;
  private final CheckRunSseService sseService;

  public CheckRunService(
      CheckRunRepository checkRuns,
      CheckStepRepository checkSteps,
      CheckRunGateRepository checkRunGates,
      ProjectHealthScoreRepository healthScores,
      ProjectRepository projects,
      GenerationRepository generations,
      AuditService audits,
      CheckRunSseService sseService) {
    this.checkRuns = checkRuns;
    this.checkSteps = checkSteps;
    this.checkRunGates = checkRunGates;
    this.healthScores = healthScores;
    this.projects = projects;
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
    return checkRuns.findByProjectId(project, pageable);
  }

  @Transactional(readOnly = true)
  public List<CheckRun> getLatestCheckRuns(UUID ownerId, UUID projectId) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    return List.of(
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project, CheckFeature.GENERATE).orElse(null),
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project, CheckFeature.REVIEW).orElse(null),
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project, CheckFeature.FIX).orElse(null),
        checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project, CheckFeature.VERIFY).orElse(null)
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
    return healthScores.findFirstByProjectIdOrderByLastComputedAtDesc(project);
  }

  @Transactional(readOnly = true)
  public List<ProjectHealthScore> getHealthScoreHistory(UUID ownerId, UUID projectId, int limit) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    List<ProjectHealthScore> scores = healthScores.findByProjectIdOrderByLastComputedAtDesc(project);
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
    
    // Feature summaries
    List<FeatureSummary> featureSummaries = List.of(
        buildFeatureSummary(userProjects, CheckFeature.GENERATE),
        buildFeatureSummary(userProjects, CheckFeature.REVIEW),
        buildFeatureSummary(userProjects, CheckFeature.FIX),
        buildFeatureSummary(userProjects, CheckFeature.VERIFY)
    );

    // Open issues by severity across all projects
    List<SeveritySummary> severitySummaries = buildSeveritySummaries(userProjects);

    // Last 10 runs across all features
    List<RunSummary> lastRuns = buildLastRuns(userProjects);

    // Trend over last 10 health score snapshots
    List<TrendPoint> trend = buildTrend(userProjects);

    // Latest health score (aggregate across projects)
    ProjectHealthScore latestHealthScore = buildAggregateHealthScore(userProjects);

    return new DashboardSummaryResponse(
        featureSummaries,
        severitySummaries,
        lastRuns,
        trend,
        latestHealthScore);
  }

  private FeatureSummary buildFeatureSummary(
      List<Project> projects, CheckFeature feature) {
    int totalRuns = 0;
    int running = 0;
    int succeeded = 0;
    int failed = 0;
    int skipped = 0;
    Instant lastRunAt = null;

    for (Project project : projects) {
      List<CheckRun> runs = checkRuns.findByProjectIdAndFeatureOrderByCreatedAtDesc(project, feature);
      totalRuns += runs.size();
      for (CheckRun run : runs) {
        switch (run.getStatus()) {
          case RUNNING -> running++;
          case SUCCEEDED -> succeeded++;
          case FAILED -> failed++;
          case SKIPPED -> skipped++;
        }
        if (lastRunAt == null || run.getCreatedAt().isAfter(lastRunAt)) {
          lastRunAt = run.getCreatedAt();
        }
      }
    }
    return new FeatureSummary(
        feature.name(), totalRuns, running, succeeded, failed, skipped, lastRunAt);
  }

  private List<SeveritySummary> buildSeveritySummaries(List<Project> projects) {
    long critical = 0, high = 0, medium = 0, low = 0, info = 0;
    for (Project project : projects) {
      critical += checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.CRITICAL);
      high += checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.HIGH);
      medium += checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.MEDIUM);
      low += checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.LOW);
      info += checkRuns.countByProjectIdAndSeverity(project.getId(), com.verireview.review.FindingSeverity.INFO);
    }
    return List.of(
        new SeveritySummary("CRITICAL", (int) critical),
        new SeveritySummary("HIGH", (int) high),
        new SeveritySummary("MEDIUM", (int) medium),
        new SeveritySummary("LOW", (int) low),
        new SeveritySummary("INFO", (int) info)
    );
  }

  private List<RunSummary> buildLastRuns(List<Project> projects) {
    List<CheckRun> allRuns = projects.stream()
        .flatMap(p -> checkRuns.findByProjectIdOrderByCreatedAtDesc(p).stream())
        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
        .limit(10)
        .toList();

    return allRuns.stream()
        .map(run -> new RunSummary(
            run.getId(),
            run.getFeature().name(),
            run.getStatus().name(),
            run.getProgress(),
            run.getStartedAt(),
            run.getFinishedAt(),
            run.getDurationMs(),
            run.getTotalSeverityCount()
        ))
        .toList();
  }

  private List<TrendPoint> buildTrend(List<Project> projects) {
    List<ProjectHealthScore> scores = projects.stream()
        .flatMap(p -> healthScores.findByProjectIdOrderByLastComputedAtDesc(p).stream())
        .sorted((a, b) -> a.getLastComputedAt().compareTo(b.getLastComputedAt()))
        .limit(10)
        .toList();

    return scores.stream()
        .map(s -> new TrendPoint(
            s.getLastComputedAt(),
            s.getGenerateScore(),
            s.getReviewScore(),
            s.getFixScore(),
            s.getVerifyScore(),
            s.getScore()
        ))
        .toList();
  }

  private ProjectHealthScore buildAggregateHealthScore(List<Project> projects) {
    if (projects.isEmpty()) {
      return new ProjectHealthScore(projects.get(0)); // Will throw but projects checked above
    }
    int totalGenerate = 0, totalReview = 0, totalFix = 0, totalVerify = 0;
    int totalCriticalHigh = 0, totalOpen = 0;
    Double totalRatio = 0.0;
    int count = 0;

    for (Project project : projects) {
      Optional<ProjectHealthScore> score = healthScores.findFirstByProjectIdOrderByLastComputedAtDesc(project);
      if (score.isPresent()) {
        ProjectHealthScore hs = score.get();
        totalGenerate += hs.getGenerateScore();
        totalReview += hs.getReviewScore();
        totalFix += hs.getFixScore();
        totalVerify += hs.getVerifyScore();
        totalCriticalHigh += hs.getOpenCriticalHigh();
        totalOpen += hs.getOpenFindingsTotal();
        if (hs.getFixedVerifiedRatio() != null) {
          totalRatio += hs.getFixedVerifiedRatio();
          count++;
        }
      }
    }

    ProjectHealthScore aggregate = new ProjectHealthScore(projects.get(0));
    aggregate.setGenerateScore(projects.isEmpty() ? 0 : totalGenerate / projects.size());
    aggregate.setReviewScore(projects.isEmpty() ? 0 : totalReview / projects.size());
    aggregate.setFixScore(projects.isEmpty() ? 0 : totalFix / projects.size());
    aggregate.setVerifyScore(projects.isEmpty() ? 0 : totalVerify / projects.size());
    aggregate.setScore(projects.isEmpty() ? 0 : (totalGenerate + totalReview + totalFix + totalVerify) / (4 * projects.size()));
    aggregate.setOpenCriticalHigh(totalCriticalHigh);
    aggregate.setOpenFindingsTotal(totalOpen);
    aggregate.setFixedVerifiedRatio(count > 0 ? totalRatio / count : null);
    return aggregate;
  }

  public record CheckStepDefinition(int stepOrder, String name) {
  }
}