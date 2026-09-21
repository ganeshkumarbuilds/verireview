package com.verireview.fix;

import com.verireview.agent.AiServiceException;
import com.verireview.agent.CodingAgent;
import com.verireview.agent.DeterministicCodingAgent;
import com.verireview.agent.GenerationCodingAgent;
import com.verireview.audit.AuditService;
import com.verireview.fix.dto.PatchResponse;
import com.verireview.generation.Generation;
import com.verireview.project.Project;
import com.verireview.review.Finding;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Patch proposal flow (Phase 9C foundation). Proposes unified diffs without modifying
 * project files, executing code, or claiming verification. Owner-scoped via FixRequest.
 * Supports both project patches and generation patches.
 */
@Service
public class PatchService {

  private final PatchRepository patches;
  private final FixRequestRepository fixRequests;
  private final DeterministicCodingAgent projectCodingAgent;
  private final GenerationCodingAgent generationCodingAgent;
  private final AuditService audits;

  public PatchService(
      PatchRepository patches,
      FixRequestRepository fixRequests,
      DeterministicCodingAgent projectCodingAgent,
      GenerationCodingAgent generationCodingAgent,
      AuditService audits) {
    this.patches = patches;
    this.fixRequests = fixRequests;
    this.projectCodingAgent = projectCodingAgent;
    this.generationCodingAgent = generationCodingAgent;
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

    // Select the appropriate coding agent based on whether the finding
    // is linked to a project or a generation
    CodingAgent codingAgent = selectCodingAgent(fixRequest);

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

    Patch patch = new Patch(fixRequest, diff);
    // Set project or generation based on the finding's review
    if (fixRequest.getFinding().getReview().getProject() != null) {
      patch.setProject(fixRequest.getFinding().getReview().getProject());
    } else if (fixRequest.getFinding().getReview().getGeneration() != null) {
      patch.setGeneration(fixRequest.getFinding().getReview().getGeneration());
    }
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

  private CodingAgent selectCodingAgent(FixRequest fixRequest) {
    Finding finding = fixRequest.getFinding();
    if (finding.getReview().getGeneration() != null) {
      return generationCodingAgent;
    }
    return projectCodingAgent;
  }

  @Transactional(readOnly = true)
  public PatchResponse getPatch(UUID ownerId, UUID patchId) {
    Patch patch = patches.findById(patchId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found"));
    // Owner check via linked FixRequest -> Finding -> Review -> Project or Generation
    validateOwnership(ownerId, patch);
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
    validateOwnership(ownerId, fixRequest);
    return fixRequest;
  }

  private void validateOwnership(UUID ownerId, FixRequest fixRequest) {
    Project project = fixRequest.getFinding().getReview().getProject();
    Generation generation = fixRequest.getFinding().getReview().getGeneration();
    if (project != null) {
      if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found");
      }
    } else if (generation != null) {
      if (!generation.getOwner().getId().equals(ownerId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found");
      }
    } else {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found");
    }
  }

  private void validateOwnership(UUID ownerId, Patch patch) {
    FixRequest fixRequest = patch.getFixRequest();
    validateOwnership(ownerId, fixRequest);
  }

  static PatchResponse toResponse(Patch patch) {
    return new PatchResponse(
        patch.getId(),
        patch.getFixRequest().getId(),
        patch.getProject() != null
            ? patch.getProject().getId()
            : patch.getGeneration() != null
                ? patch.getGeneration().getId()
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
