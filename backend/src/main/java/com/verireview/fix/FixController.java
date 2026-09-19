package com.verireview.fix;

import com.verireview.fix.dto.CreateFixRequestRequest;
import com.verireview.fix.dto.FixRequestResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Fix-request approval gate (API_DESIGN §2, Phase 9A). Thin by rule —
 * ownership, gating, and audit live in {@link FixService}.
 */
@RestController
public class FixController {

  private final FixService fixRequests;

  public FixController(FixService fixRequests) {
    this.fixRequests = fixRequests;
  }

  @PostMapping("/api/v1/findings/{id}/fix-requests")
  public ResponseEntity<FixRequestResponse> create(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID findingId,
      @Valid @RequestBody(required = false) CreateFixRequestRequest request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(fixRequests.create(principal.getId(), findingId, request));
  }

  @GetMapping("/api/v1/findings/{id}/fix-requests")
  public ResponseEntity<List<FixRequestResponse>> history(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID findingId) {
    return ResponseEntity.ok(fixRequests.history(principal.getId(), findingId));
  }

  @GetMapping("/api/v1/fix-requests/{fixId}")
  public ResponseEntity<FixRequestResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("fixId") UUID fixRequestId) {
    return ResponseEntity.ok(fixRequests.get(principal.getId(), fixRequestId));
  }
}
