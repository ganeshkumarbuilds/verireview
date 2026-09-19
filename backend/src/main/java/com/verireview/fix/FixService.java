package com.verireview.fix;

import com.verireview.audit.AuditService;
import com.verireview.fix.dto.CreateFixRequestRequest;
import com.verireview.fix.dto.FixRequestResponse;
import com.verireview.project.Project;
import com.verireview.review.Finding;
import com.verireview.review.FindingRepository;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fix-request approval gate (Phase 9A foundation). Every lookup is
 * owner-scoped through finding → review → project: foreign or
 * soft-deleted projects answer 404 (SECURITY_DESIGN T6). No patch, coding
 * agent, or verification lives here — those arrive in later Phase 9 work.
 */
@Service
public class FixService {

  private static final List<FixRequestStatus> OPEN =
      List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS);

  private final FixRequestRepository fixRequests;
  private final FindingRepository findings;
  private final UserRepository users;
  private final AuditService audits;

  public FixService(
      FixRequestRepository fixRequests,
      FindingRepository findings,
      UserRepository users,
      AuditService audits) {
    this.fixRequests = fixRequests;
    this.findings = findings;
    this.users = users;
    this.audits = audits;
  }

  @Transactional
  public FixRequestResponse create(UUID ownerId, UUID findingId, CreateFixRequestRequest request) {
    Finding finding = ownedFinding(ownerId, findingId);
    if (fixRequests.existsByFindingIdAndStatusIn(finding.getId(), OPEN)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An open fix request already exists for this finding");
    }
    User requester = users.getReferenceById(ownerId);
    FixRequest fixRequest = new FixRequest(finding, requester);
    if (request != null && request.scopeNote() != null && !request.scopeNote().isBlank()) {
      fixRequest.setScopeNote(request.scopeNote().trim());
    }
    try {
      fixRequests.saveAndFlush(fixRequest);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An open fix request already exists for this finding");
    }
    audits.record(requester, "FIX_REQUEST_CREATED", "fix_request",
        fixRequest.getId().toString());
    return toResponse(fixRequest);
  }

  @Transactional(readOnly = true)
  public FixRequestResponse get(UUID ownerId, UUID fixRequestId) {
    return toResponse(ownedFixRequest(ownerId, fixRequestId));
  }

  @Transactional(readOnly = true)
  public List<FixRequestResponse> history(UUID ownerId, UUID findingId) {
    Finding finding = ownedFinding(ownerId, findingId);
    return fixRequests.findByFindingIdOrderByCreatedAtDesc(finding.getId()).stream()
        .map(FixService::toResponse)
        .toList();
  }

  private Finding ownedFinding(UUID ownerId, UUID findingId) {
    Finding finding = findings.findById(findingId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found"));
    Project project = finding.getReview().getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found");
    }
    return finding;
  }

  private FixRequest ownedFixRequest(UUID ownerId, UUID fixRequestId) {
    FixRequest fixRequest = fixRequests.findById(fixRequestId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found"));
    Project project = fixRequest.getFinding().getReview().getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix request not found");
    }
    return fixRequest;
  }

  static FixRequestResponse toResponse(FixRequest fixRequest) {
    Finding finding = fixRequest.getFinding();
    return new FixRequestResponse(
        fixRequest.getId(),
        finding.getId(),
        finding.getReview().getProject().getId(),
        fixRequest.getRequestedBy().getId(),
        fixRequest.getStatus(),
        fixRequest.getScopeNote(),
        fixRequest.getError(),
        fixRequest.getCreatedAt(),
        fixRequest.getUpdatedAt());
  }
}
