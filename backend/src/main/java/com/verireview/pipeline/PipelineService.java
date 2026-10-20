package com.verireview.pipeline;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionRepository;
import com.verireview.agent.AgentExecutionStatus;
import com.verireview.check.CheckFeature;
import com.verireview.check.CheckRun;
import com.verireview.check.CheckRunRepository;
import com.verireview.check.CheckStatus;
import com.verireview.execution.ExecutionRun;
import com.verireview.execution.ExecutionRunRepository;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.FixRequestStatus;
import com.verireview.pipeline.dto.PipelineResponse;
import com.verireview.pipeline.dto.PipelineStageResponse;
import com.verireview.project.Project;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.review.Finding;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read model for the visible agent pipeline: Import -> Analyze -> Review -> Fix -> Verify.
 *
 * <p>States are always derived from real records (project files, {@code reviews},
 * {@code agent_executions}, {@code check_runs}/{@code check_steps}, and
 * {@code execution_runs}). A stage that never ran is WAITING; a skipped sandbox
 * is SKIPPED, never COMPLETED. No endpoint here returns 500 for a project with
 * no data.
 */
@Service
public class PipelineService {

  private final ProjectRepository projects;
  private final ProjectFileRepository files;
  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final CheckRunRepository checkRuns;
  private final ExecutionRunRepository executions;
  private final AgentExecutionRepository agentExecutions;
  private final FixRequestRepository fixRequests;

  public PipelineService(
      ProjectRepository projects,
      ProjectFileRepository files,
      ReviewRepository reviews,
      FindingRepository findings,
      CheckRunRepository checkRuns,
      ExecutionRunRepository executions,
      AgentExecutionRepository agentExecutions,
      FixRequestRepository fixRequests) {
    this.projects = projects;
    this.files = files;
    this.reviews = reviews;
    this.findings = findings;
    this.checkRuns = checkRuns;
    this.executions = executions;
    this.agentExecutions = agentExecutions;
    this.fixRequests = fixRequests;
  }

  @Transactional(readOnly = true)
  public PipelineResponse getPipeline(UUID ownerId, UUID projectId) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

    return new PipelineResponse(
        projectId,
        List.of(
            importStage(project),
            analyzeStage(project),
            reviewStage(project),
            fixStage(project),
            verifyStage(project)));
  }

  private PipelineStageResponse importStage(Project project) {
    long fileCount = files.countByProjectId(project.getId());
    if (fileCount == 0) {
      return stage("import", "Import", "WAITING", null, null, null,
          "No files imported yet", null, null);
    }
    Instant at = project.getCreatedAt();
    return stage("import", "Import", "COMPLETED", at, at, null,
        fileCount + " file(s) imported", null, null);
  }

  private PipelineStageResponse analyzeStage(Project project) {
    Review review = reviews.findFirstByProjectIdOrderByCreatedAtDesc(project.getId()).orElse(null);
    if (review == null) {
      return stage("analyze", "Analyze", "WAITING", null, null, null,
          "No analysis run yet", null, null);
    }
    List<AgentExecution> agentRuns =
        agentExecutions.findByReviewIdOrderByCreatedAtAsc(review.getId());
    String agents = agentRuns.isEmpty()
        ? null
        : agentRuns.stream()
            .map(e -> e.getAgentType().name())
            .distinct()
            .collect(Collectors.joining(", "));
    String detail = agents == null ? "Analysis run" : "Agents: " + agents;

    return switch (review.getStatus()) {
      case QUEUED -> stage("analyze", "Analyze", "WAITING", null, null, null, detail, null, null);
      case RUNNING -> stage("analyze", "Analyze", "RUNNING",
          review.getStartedAt(), null, null, detail, null, null);
      case COMPLETED -> stage("analyze", "Analyze", "COMPLETED",
          review.getStartedAt(), review.getFinishedAt(), review.getDurationMs(), detail, null, null);
      case FAILED -> {
        String error = agentRuns.stream()
            .filter(e -> e.getStatus() == AgentExecutionStatus.FAILED)
            .map(AgentExecution::getError)
            .filter(e -> e != null && !e.isBlank())
            .findFirst()
            .orElse(review.getError());
        yield stage("analyze", "Analyze", "FAILED",
            review.getStartedAt(), review.getFinishedAt(), review.getDurationMs(),
            detail, sanitize(error), null);
      }
    };
  }

  private PipelineStageResponse reviewStage(Project project) {
    Review review = reviews.findFirstByProjectIdOrderByCreatedAtDesc(project.getId()).orElse(null);
    if (review == null) {
      return stage("review", "Review", "WAITING", null, null, null,
          "Findings appear after the analysis completes", null, null);
    }
    if (review.getStatus() == ReviewStatus.RUNNING || review.getStatus() == ReviewStatus.QUEUED) {
      return stage("review", "Review", "RUNNING",
          review.getStartedAt(), null, null, "Reviewing findings", null, null);
    }
    if (review.getStatus() == ReviewStatus.FAILED) {
      return stage("review", "Review", "FAILED",
          review.getStartedAt(), review.getFinishedAt(), review.getDurationMs(),
          "Review did not complete", sanitize(review.getError()), null);
    }
    List<Finding> reviewFindings = findings.findByReviewId(review.getId());
    long critical = count(reviewFindings, FindingSeverity.CRITICAL);
    long high = count(reviewFindings, FindingSeverity.HIGH);
    long medium = count(reviewFindings, FindingSeverity.MEDIUM);
    long low = count(reviewFindings, FindingSeverity.LOW);
    long info = count(reviewFindings, FindingSeverity.INFO);
    String detail = reviewFindings.size() + " finding(s) — "
        + critical + " critical, " + high + " high, " + medium + " medium, "
        + low + " low, " + info + " info";
    return stage("review", "Review", "COMPLETED",
        review.getStartedAt(), review.getFinishedAt(), review.getDurationMs(), detail, null, null);
  }

  private PipelineStageResponse fixStage(Project project) {
    Optional<CheckRun> run = checkRuns
        .findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project.getId(), CheckFeature.FIX);
    if (run.isPresent()) {
      return fromCheckRun("fix", "Fix", run.get());
    }
    long requested = fixRequests
        .countByFindingReviewProjectIdAndStatus(project.getId(), FixRequestStatus.REQUESTED);
    long inProgress = fixRequests
        .countByFindingReviewProjectIdAndStatus(project.getId(), FixRequestStatus.IN_PROGRESS);
    long completed = fixRequests
        .countByFindingReviewProjectIdAndStatus(project.getId(), FixRequestStatus.COMPLETED);
    if (requested + inProgress + completed == 0) {
      return stage("fix", "Fix", "WAITING", null, null, null,
          "No fix run yet — select findings to fix", null, null);
    }
    String detail = requested + " approved, " + inProgress + " in progress, "
        + completed + " completed";
    String state = inProgress > 0 ? "RUNNING" : "WAITING";
    return stage("fix", "Fix", state, null, null, null, detail, null, null);
  }

  private PipelineStageResponse verifyStage(Project project) {
    Optional<CheckRun> run = checkRuns
        .findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(project.getId(), CheckFeature.VERIFY);
    if (run.isPresent()) {
      return fromCheckRun("verify", "Verify", run.get());
    }
    List<ExecutionRun> execs = executions.findByProjectIdOrderByCreatedAtDesc(project.getId());
    if (execs.isEmpty()) {
      return stage("verify", "Verify", "WAITING", null, null, null,
          "No verification run yet", null, null);
    }
    ExecutionRun exec = execs.get(0);
    return switch (exec.getStatus()) {
      case PENDING -> stage("verify", "Verify", "WAITING", null, null, null,
          "Verification queued", null, null);
      case RUNNING -> stage("verify", "Verify", "RUNNING",
          exec.getCreatedAt(), null, exec.getDurationMs(), "Running build and tests", null, null);
      case SKIPPED -> stage("verify", "Verify", "SKIPPED",
          exec.getCreatedAt(), exec.getUpdatedAt(), exec.getDurationMs(),
          "Not verified: sandbox unavailable", null, null);
      case SUCCESS -> stage("verify", "Verify", "COMPLETED",
          exec.getCreatedAt(), exec.getUpdatedAt(), exec.getDurationMs(), "Build passed", null, null);
      case FAILURE -> stage("verify", "Verify", "FAILED",
          exec.getCreatedAt(), exec.getUpdatedAt(), exec.getDurationMs(),
          "Build or tests failed", sanitize(firstNonBlank(exec.getStderr(), exec.getStdout())), null);
      case TIMEOUT -> stage("verify", "Verify", "FAILED",
          exec.getCreatedAt(), exec.getUpdatedAt(), exec.getDurationMs(),
          "Verification timed out", "The build or tests did not finish in time", null);
    };
  }

  private PipelineStageResponse fromCheckRun(String key, String label, CheckRun run) {
    boolean skipped = run.getStatus() == CheckStatus.SKIPPED;
    String detail = skipped
        ? "Not verified: sandbox unavailable"
        : (run.getCurrentStep() != null ? run.getCurrentStep() : label + " run");
    return stage(
        key,
        label,
        mapState(run.getStatus()),
        run.getStartedAt(),
        run.getFinishedAt(),
        run.getDurationMs(),
        detail,
        sanitize(run.getErrorMessage()),
        run.getId());
  }

  private static String mapState(CheckStatus status) {
    return switch (status) {
      case QUEUED, PENDING -> "WAITING";
      case RUNNING -> "RUNNING";
      case SUCCEEDED -> "COMPLETED";
      case FAILED -> "FAILED";
      case SKIPPED -> "SKIPPED";
    };
  }

  private static long count(List<Finding> list, FindingSeverity severity) {
    return list.stream().filter(f -> f.getSeverity() == severity).count();
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) return a;
    return b;
  }

  /**
   * Converts a raw error into a short, plain-language line. Never returns a stack
   * trace: only the first line is kept and common exception class prefixes are
   * stripped.
   */
  static String sanitize(String raw) {
    return com.verireview.common.ErrorMessages.sanitize(raw);
  }

  private static PipelineStageResponse stage(
      String key, String label, String state, Instant startedAt, Instant finishedAt,
      Long durationMs, String detail, String error, UUID checkRunId) {
    return new PipelineStageResponse(
        key, label, state, startedAt, finishedAt, durationMs, detail, error, checkRunId);
  }
}
