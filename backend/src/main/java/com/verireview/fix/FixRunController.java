package com.verireview.fix;

import com.verireview.fix.dto.BulkFixRequestCreate;
import com.verireview.fix.dto.BulkFixRequestCreated;
import com.verireview.fix.dto.FixRunDetailResponse;
import com.verireview.fix.dto.StartFixRunRequest;
import com.verireview.security.VeriReviewUserDetails;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-scoped find -&gt; ask permission -&gt; fix -&gt; re-check flow.
 *
 * <p>Two explicit steps: first the user consents (bulk fix requests), then a fix
 * run is started. The backend enforces the permission gate on the second step.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class FixRunController {

  private final FixRunService fixRuns;

  public FixRunController(FixRunService fixRuns) {
    this.fixRuns = fixRuns;
  }

  /** Consent step: create approved fix requests for the selected findings. */
  @PostMapping("/fix-requests")
  public ResponseEntity<BulkFixRequestCreated> createFixRequests(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId,
      @Valid @RequestBody BulkFixRequestCreate request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(fixRuns.createFixRequests(principal.getId(), projectId, request));
  }

  /**
   * Starts a fix run. Rejected with 409 unless an approved fix request exists for
   * the user and every selected finding.
   */
  @PostMapping("/fix-runs")
  public ResponseEntity<FixRunDetailResponse> startFixRun(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId,
      @Valid @RequestBody StartFixRunRequest request) {
    return ResponseEntity.ok(fixRuns.startFixRun(principal.getId(), projectId, request));
  }

  /** List all fix runs for a project. */
  @GetMapping("/fix-runs")
  public ResponseEntity<List<FixRunDetailResponse>> listFixRuns(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId) {
    return ResponseEntity.ok(fixRuns.listFixRuns(principal.getId(), projectId));
  }

  /** Full detail of a fix run and its re-check (used to render the after-fix summary). */
  @GetMapping("/fix-runs/{checkRunId}")
  public ResponseEntity<FixRunDetailResponse> getFixRun(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId,
      @PathVariable("checkRunId") UUID checkRunId) {
    return ResponseEntity.ok(fixRuns.getFixRun(principal.getId(), projectId, checkRunId));
  }
}
