package com.verireview.fix;

import com.verireview.agent.AiServiceException;
import com.verireview.agent.CodingAgent;
import com.verireview.audit.AuditService;
import com.verireview.fix.dto.PatchResponse;
import com.verireview.project.Project;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Patch proposal flow (Phase 9C foundation). Proposes unified diffs without modifying
 * project files, executing code, or claiming verification. Owner-scoped via FixRequest.
 */
@Service
public class PatchService {

  private final PatchRepository patches;
  private final FixRequestRepository fixRequests;
  private final CodingAgent codingAgent;
  private final AuditService audits;

  public PatchService(
      PatchRepository patches,
      FixRequestRepository fixRequests,
      CodingAgent codingAgent,
      AuditService audits) {
    this.patches = patches;
    this.fixRequests = fixRequests;
    this.codingAgent = codingAgent;
    this.audits = audits;
  }

  @Transactional
  public PatchResponse proposePatch(UUID ownerId, UUID fixRequestId) {
    FixRequest fixRequest = ownedFixRequest(ownerId, fixRequestId);

    // Only allow proposing from REQUESTED state to keep lifecycle clean.
    if (fixRequest.getStatus() != FixRequestStatus.REQUESTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "FixRequest must be in REQUESTED state to propose a patch");
    }

    CodingAgent.Proposal proposal;
    try {
      proposal = codingAgent.propose(fixRequest);
    } catch (AiServiceException e) {
      // Controlled failure: do not create patch, keep FixRequest in REQUESTED
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
    if (proposal == null || proposal.diff() == null || proposal.diff().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI coding returned empty diff");
    }
    // Strict gate: malformed, absolute, traversal, binary, or oversized diffs
    // are rejected here — nothing is persisted and nothing is applied.
    final UnifiedDiffValidator.DiffStats stats;
    try {
      stats = UnifiedDiffValidator.validate(proposal.diff());
    } catch (UnifiedDiffValidator.DiffValidationException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
    String diff = proposal.diff().trim();

    Project project = fixRequest.getFinding().getReview().getProject();

    Patch patch = new Patch(fixRequest, diff);
    patch.setProject(project);
    // Authoritative counts derived from the diff itself, never AI-reported numbers.
    patch.setFilesChanged(stats.filesChanged());
    patch.setAdditions(stats.additions());
    patch.setDeletions(stats.deletions());
    patch.setStatus(PatchStatus.PROPOSED);

    patches.save(patch);

    // Transition FixRequest to IN_PROGRESS to reflect work started
    fixRequest.setStatus(FixRequestStatus.IN_PROGRESS);

    audits.record(
        fixRequest.getRequestedBy(),
        "PATCH_PROPOSED",
        "patch",
        patch.getId().toString());

    return toResponse(patch);
  }

  @Transactional(readOnly = true)
  public PatchResponse getPatch(UUID ownerId, UUID patchId) {
    Patch patch = patches.findById(patchId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found"));
    // Owner check via linked FixRequest -> Finding -> Review -> Project
    Project project = patch.getFixRequest().getFinding().getReview().getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found");
    }
    return toResponse(patch);
  }

  @Transactional(readOnly = true)
  public PatchResponse getPatchForFixRequest(UUID ownerId, UUID fixRequestId) {
    FixRequest fixRequest = ownedFixRequest(ownerId, fixRequestId);
    Patch patch = patches.findTopByFixRequestIdOrderByCreatedAtDesc(fixRequest.getId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found for fix request"));
    return toResponse(patch);
  }

  private FixRequest ownedFixRequest(UUID ownerId, UUID fixRequestId) {
    FixRequest fixRequest = fixRequests.findById(fixRequestId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found"));
    Project project = fixRequest.getFinding().getReview().getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found");
    }
    return fixRequest;
  }

  static PatchResponse toResponse(Patch patch) {
    return new PatchResponse(
        patch.getId(),
        patch.getFixRequest().getId(),
        patch.getProject() != null
            ? patch.getProject().getId()
            : patch.getFixRequest().getFinding().getReview().getProject().getId(),
        patch.getDiff(),
        patch.getFilesChanged(),
        patch.getAdditions(),
        patch.getDeletions(),
        patch.getStatus(),
        patch.getValidationError(),
        patch.getCreatedAt(),
        patch.getUpdatedAt());
  }
}
