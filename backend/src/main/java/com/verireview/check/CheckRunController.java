package com.verireview.check;

import com.verireview.check.dto.CheckRunResponse;
import com.verireview.check.dto.CheckStepResponse;
import com.verireview.check.dto.CheckRunGateResponse;
import com.verireview.check.dto.DashboardSummaryResponse;
import com.verireview.common.PagedResponse;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.security.VeriReviewUserDetails;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class CheckRunController {

  private final CheckRunService checkRuns;
  private final ProjectRepository projects;
  private final CheckRunSseService sseService;

  public CheckRunController(
      CheckRunService checkRuns,
      ProjectRepository projects,
      CheckRunSseService sseService) {
    this.checkRuns = checkRuns;
    this.projects = projects;
    this.sseService = sseService;
  }

  /**
   * Gets the latest check run for each feature (GENERATE, REVIEW, FIX, VERIFY) for a project.
   */
  @GetMapping("/projects/{id}/checks")
  public ResponseEntity<List<CheckRunResponse>> getChecks(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID projectId) {
    List<CheckRun> runs = checkRuns.getLatestCheckRuns(principal.getId(), projectId);
    return ResponseEntity.ok(runs.stream().map(this::toResponse).toList());
  }

  /**
   * Gets a specific check run with its steps and gates.
   */
  @GetMapping("/projects/{projectId}/runs/{runId}")
  public ResponseEntity<CheckRunResponse> getCheckRun(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId,
      @PathVariable("runId") UUID runId) {
    CheckRun run = checkRuns.getCheckRun(principal.getId(), runId);
    if (!run.getProject().getId().equals(projectId)) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(toResponseWithDetails(run));
  }

  /**
   * Server-Sent Events stream for real-time check run updates.
   * Falls back to 3s polling if SSE is not available.
   */
  @GetMapping(value = "/projects/{id}/checks/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamChecks(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID projectId) {
    return sseService.subscribe(principal.getId(), projectId);
  }

  /**
   * Dashboard summary: per-feature counts, open issues by severity, last 10 runs, trend.
   */
  @GetMapping("/dashboard/summary")
  public ResponseEntity<DashboardSummaryResponse> getDashboardSummary(
      @AuthenticationPrincipal VeriReviewUserDetails principal) {
    DashboardSummaryResponse summary = checkRuns.getDashboardSummary(principal.getId());
    return ResponseEntity.ok(summary);
  }

  /**
   * Server-Sent Events stream for dashboard summary updates.
   */
  @GetMapping(value = "/dashboard/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamDashboard(
      @AuthenticationPrincipal VeriReviewUserDetails principal) {
    return sseService.subscribeDashboard(principal.getId());
  }

  private CheckRunResponse toResponse(CheckRun run) {
    return new CheckRunResponse(
        run.getId(),
        run.getProject().getId(),
        run.getGeneration() != null ? run.getGeneration().getId() : null,
        run.getFeature().name(),
        run.getStatus().name(),
        run.getProgress(),
        run.getCurrentStep(),
        run.getErrorMessage(),
        run.getStartedAt(),
        run.getFinishedAt(),
        run.getDurationMs(),
        run.getSeverityCritical(),
        run.getSeverityHigh(),
        run.getSeverityMedium(),
        run.getSeverityLow(),
        run.getSeverityInfo(),
        run.getCreatedAt(),
        run.getUpdatedAt());
  }

  private CheckRunResponse toResponseWithDetails(CheckRun run) {
    CheckRunResponse response = toResponse(run);
    List<CheckStepResponse> steps = checkRuns.getSteps(run.getProject().getOwner().getId(), run.getId())
        .stream().map(this::toStepResponse).toList();
    List<CheckRunGateResponse> gates = checkRuns.getGates(run.getProject().getOwner().getId(), run.getId())
        .stream().map(this::toGateResponse).toList();
    return new CheckRunResponse(
        response.id(),
        response.projectId(),
        response.generationId(),
        response.feature(),
        response.status(),
        response.progress(),
        response.currentStep(),
        response.errorMessage(),
        response.startedAt(),
        response.finishedAt(),
        response.durationMs(),
        response.severityCritical(),
        response.severityHigh(),
        response.severityMedium(),
        response.severityLow(),
        response.severityInfo(),
        response.createdAt(),
        response.updatedAt(),
        steps,
        gates);
  }

  private CheckStepResponse toStepResponse(CheckStep step) {
    return new CheckStepResponse(
        step.getId(),
        step.getCheckRun().getId(),
        step.getStepOrder(),
        step.getName(),
        step.getStatus().name(),
        step.getProgress(),
        step.getCurrentMessage(),
        step.getErrorMessage(),
        step.getStartedAt(),
        step.getFinishedAt(),
        step.getDurationMs(),
        step.getLogTail());
  }

  private CheckRunGateResponse toGateResponse(CheckRunGate gate) {
    return new CheckRunGateResponse(
        gate.getId(),
        gate.getCheckRun().getId(),
        gate.getGateName(),
        gate.getGateDescription(),
        gate.isPassed(),
        gate.getEvidence(),
        gate.getDetails());
  }
}