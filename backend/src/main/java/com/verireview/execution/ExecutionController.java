package com.verireview.execution;

import com.verireview.execution.dto.ExecutionRunResponse;
import com.verireview.security.VeriReviewUserDetails;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExecutionController {

  private final ExecutionService executions;

  public ExecutionController(ExecutionService executions) {
    this.executions = executions;
  }

  @PostMapping("/api/v1/projects/{projectId}/execute")
  public ResponseEntity<ExecutionRunResponse> execute(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId,
      @RequestParam(value = "patchId", required = false) UUID patchId) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(executions.execute(principal.getId(), projectId, patchId));
  }

  @GetMapping("/api/v1/projects/{projectId}/executions")
  public ResponseEntity<List<ExecutionRunResponse>> list(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("projectId") UUID projectId) {
    return ResponseEntity.ok(executions.listRuns(principal.getId(), projectId));
  }

  @GetMapping("/api/v1/executions/{executionId}")
  public ResponseEntity<ExecutionRunResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("executionId") UUID executionId) {
    return ResponseEntity.ok(executions.getRun(principal.getId(), executionId));
  }
}
