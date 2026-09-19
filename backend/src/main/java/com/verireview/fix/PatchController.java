package com.verireview.fix;

import com.verireview.fix.dto.PatchResponse;
import com.verireview.security.VeriReviewUserDetails;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patch proposal endpoints (Phase 9C foundation). Proposals are stored, never applied.
 */
@RestController
public class PatchController {

  private final PatchService patches;
  private final PatchApplicationService patchApplication;

  public PatchController(PatchService patches, PatchApplicationService patchApplication) {
    this.patches = patches;
    this.patchApplication = patchApplication;
  }

  @PostMapping("/api/v1/fix-requests/{fixRequestId}/patch")
  public ResponseEntity<PatchResponse> propose(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("fixRequestId") UUID fixRequestId) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(patches.proposePatch(principal.getId(), fixRequestId));
  }

  @GetMapping("/api/v1/fix-requests/{fixRequestId}/patch")
  public ResponseEntity<PatchResponse> getForFixRequest(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("fixRequestId") UUID fixRequestId) {
    return ResponseEntity.ok(patches.getPatchForFixRequest(principal.getId(), fixRequestId));
  }

  @GetMapping("/api/v1/patches/{patchId}")
  public ResponseEntity<PatchResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("patchId") UUID patchId) {
    return ResponseEntity.ok(patches.getPatch(principal.getId(), patchId));
  }

  @PostMapping("/api/v1/patches/{patchId}/apply")
  public ResponseEntity<PatchResponse> apply(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("patchId") UUID patchId) {
    return ResponseEntity.ok(patchApplication.applyPatch(principal.getId(), patchId));
  }
}
