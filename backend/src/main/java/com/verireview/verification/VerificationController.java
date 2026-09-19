package com.verireview.verification;

import com.verireview.security.VeriReviewUserDetails;
import com.verireview.verification.dto.VerificationRunResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerificationController {

  private final VerificationService verifications;

  public VerificationController(VerificationService verifications) {
    this.verifications = verifications;
  }

  @PostMapping("/api/v1/executions/{executionId}/verification")
  public ResponseEntity<VerificationRunResponse> verify(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("executionId") UUID executionId) {
    var run = verifications.verify(principal.getId(), executionId);
    var body = VerificationService.toResponse(run);
    HttpStatus status = run.getVerdict() == VerificationVerdict.VERIFIED ? HttpStatus.CREATED : HttpStatus.OK;
    // Use CREATED for VERIFIED, OK for REJECTED to distinguish, but both are successful creations
    // For simplicity, return 201 for VERIFIED, 200 for REJECTED
    return ResponseEntity.status(status).body(body);
  }

  @GetMapping("/api/v1/verifications/{verificationId}")
  public ResponseEntity<VerificationRunResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("verificationId") UUID verificationId) {
    var run = verifications.getVerification(principal.getId(), verificationId);
    return ResponseEntity.ok(VerificationService.toResponse(run));
  }

  @GetMapping("/api/v1/patches/{patchId}/verifications")
  public ResponseEntity<List<VerificationRunResponse>> listByPatch(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("patchId") UUID patchId) {
    var list = verifications.listByPatch(principal.getId(), patchId).stream()
        .map(VerificationService::toResponse)
        .toList();
    return ResponseEntity.ok(list);
  }

  @GetMapping("/api/v1/executions/{executionId}/verification")
  public ResponseEntity<VerificationRunResponse> getByExecution(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("executionId") UUID executionId) {
    var run = verifications.getByExecution(principal.getId(), executionId);
    return ResponseEntity.ok(VerificationService.toResponse(run));
  }
}
