package com.verireview.generation;

import com.verireview.common.PagedResponse;
import com.verireview.generation.dto.CreateGenerationRequest;
import com.verireview.generation.dto.ApproveGenerationFixRequest;
import com.verireview.generation.dto.CreateGenerationRevisionRequest;
import com.verireview.generation.dto.GenerationFileContentView;
import com.verireview.generation.dto.GenerationFileView;
import com.verireview.generation.dto.GenerationResponse;
import com.verireview.generation.dto.GenerationFixPreviewResponse;
import com.verireview.generation.dto.GenerationRevisionResponse;
import com.verireview.generation.dto.PlanPreviewRequest;
import com.verireview.generation.dto.PlanPreviewResponse;
import com.verireview.generation.dto.RetryGenerationStageRequest;
import com.verireview.generation.dto.RestructureProposalRequest;
import com.verireview.generation.dto.RestructureView;
import com.verireview.generation.dto.StartGenerationRequest;
import com.verireview.generation.dto.UpdateGenerationRequest;
import com.verireview.security.VeriReviewUserDetails;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Generation API boundary.
 *
 * <p>The controller remains intentionally thin. Ownership, state-transition
 * validation, patch validation, audit handling, and generation workflow
 * orchestration belong to {@link GenerationService}.
 *
 * <p>The rebuild endpoint accepts an applied generation patch and queues the
 * generation for another build/verification/review cycle. The backend remains
 * authoritative for the actual lifecycle state.
 */
@RestController
@RequestMapping("/api/v1/generations")
public class GenerationController {

  private final GenerationService generations;
  private final GenerationRestructureService restructures;
  private final GenerationEventStream eventStream;

  public GenerationController(GenerationService generations,
      GenerationRestructureService restructures,
      GenerationEventStream eventStream) {
    this.generations = generations;
    this.restructures = restructures;
    this.eventStream = eventStream;
  }

  @PostMapping
  public ResponseEntity<GenerationResponse> create(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody CreateGenerationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(generations.create(principal.getId(), request));
  }

  @GetMapping
  public ResponseEntity<PagedResponse<GenerationResponse>> list(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PageableDefault(
          size = 20,
          sort = "createdAt",
          direction = org.springframework.data.domain.Sort.Direction.DESC)
          Pageable pageable) {
    return ResponseEntity.ok(generations.list(principal.getId(), pageable));
  }

  @PostMapping("/plan-preview")
  public ResponseEntity<PlanPreviewResponse> previewPlan(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody PlanPreviewRequest request) {
    return ResponseEntity.ok(generations.previewPlan(principal.getId(), request));
  }

  @GetMapping("/{id}")
  public ResponseEntity<GenerationResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(generations.get(principal.getId(), id));
  }

  @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    generations.get(principal.getId(), id);
    return eventStream.subscribe(id);
  }

  /** Files actually present in the latest generated workspace (paths only). */
  @GetMapping("/{id}/files")
  public ResponseEntity<List<GenerationFileView>> files(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(generations.listFiles(principal.getId(), id));
  }

  @GetMapping("/{id}/file")
  public ResponseEntity<GenerationFileContentView> file(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @RequestParam("path") String path) {
    return ResponseEntity.ok(generations.readFile(principal.getId(), id, path));
  }

  /** Hands a reviewed generation to Review by materializing a Project. */
  @PostMapping("/{id}/send-to-review")
  public ResponseEntity<GenerationResponse> sendToReview(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(generations.sendToReview(principal.getId(), id));
  }

  @PostMapping("/{id}/restructure/proposals")
  public ResponseEntity<RestructureView> proposeRestructure(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody RestructureProposalRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(restructures.propose(principal.getId(), id, request));
  }

  @GetMapping("/{id}/restructure/proposals")
  public ResponseEntity<List<RestructureView>> listRestructures(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(restructures.list(principal.getId(), id));
  }

  @PostMapping("/{id}/restructure/proposals/{proposalId}/apply")
  public ResponseEntity<RestructureView> applyRestructure(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @PathVariable("proposalId") UUID proposalId) {
    return ResponseEntity.ok(restructures.apply(principal.getId(), id, proposalId));
  }

  @PostMapping("/{id}/restructure/revert")
  public ResponseEntity<RestructureView> revertRestructure(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(restructures.revert(principal.getId(), id));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<GenerationResponse> update(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody(required = false) UpdateGenerationRequest request) {
    return ResponseEntity.ok(generations.update(principal.getId(), id, request));
  }

  @PostMapping("/{id}/start")
  public ResponseEntity<GenerationResponse> start(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody(required = false) StartGenerationRequest request) {
    return ResponseEntity.ok(generations.start(principal.getId(), id, request));
  }

  @PostMapping("/{id}/revisions")
  public ResponseEntity<GenerationRevisionResponse> appendRevision(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody CreateGenerationRevisionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(generations.appendRevision(principal.getId(), id, request));
  }

  @GetMapping("/{id}/revisions")
  public ResponseEntity<List<GenerationRevisionResponse>> listRevisions(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(generations.listRevisions(principal.getId(), id));
  }

  /**
   * Creates FixRequests for every OPEN finding of the generation's latest
   * review. Approval gate only: records the user's permission to fix and
   * never mutates source code. Patches are still proposed and applied
   * per finding through the existing fix/patch flow, followed by the
   * rebuild endpoint. Only REVIEWED generations qualify (409 otherwise).
   */
  @PostMapping("/{id}/fix-requests")
  public ResponseEntity<BulkFixRequestsResponse> createFixRequests(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody(required = false) FixRequestsBulkRequest request) {
    GenerationService.BulkFixRequestResult result =
        generations.createGenerationFixRequests(principal.getId(), id,
            request == null ? null : request.scopeNote());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new BulkFixRequestsResponse(
            result.reviewId(), result.created(), result.skippedOpen()));
  }

  @PostMapping("/{id}/fix-approvals")
  public ResponseEntity<GenerationResponse> approveFixes(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody(required = false) ApproveGenerationFixRequest request) {
    return ResponseEntity.accepted().body(generations.approveFixes(
        principal.getId(),
        id,
        request == null ? null : request.findingIds(),
        request == null ? null : request.apiKey()));
  }

  @PostMapping("/{id}/fix-approvals/preview")
  public ResponseEntity<GenerationFixPreviewResponse> previewFixFiles(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody(required = false) ApproveGenerationFixRequest request) {
    return ResponseEntity.ok(generations.previewFixFiles(
        principal.getId(), id, request == null ? null : request.findingIds()));
  }

  @PostMapping("/{id}/fix-rounds/{round}/revert")
  public ResponseEntity<GenerationResponse> revertFixRound(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @PathVariable("round") int round) {
    return ResponseEntity.ok(generations.revertFixRound(principal.getId(), id, round));
  }

  @PostMapping("/{id}/agent-executions/{executionId}/retry")
  public ResponseEntity<GenerationResponse> retryFailedStage(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @PathVariable("executionId") UUID executionId,
      @Valid @RequestBody(required = false) RetryGenerationStageRequest request) {
    return ResponseEntity.accepted()
        .body(generations.retryFailedStage(
            principal.getId(), id, executionId, request == null ? null : request.apiKey()));
  }
  /**
   * Rebuilds an existing generated project after an applied generation patch.
   *
   * <p>The service validates that the authenticated user owns the generation,
   * that the supplied patch belongs to that generation, and that the patch is
   * already {@code APPLIED}. The endpoint therefore does not trust the client
   * to choose an arbitrary workspace or generation.
   */
  @PostMapping("/{id}/rebuild")
  public ResponseEntity<Void> triggerRebuild(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody TriggerRebuildRequest request) {
    generations.triggerRebuildAndReverify(id, principal.getId(), request.patchId());
    return ResponseEntity.accepted().build();
  }

  /**
   * Downloads a reviewed generated project as a ZIP archive.
   *
   * <p>The backend is the sole authority on whether a generation is downloadable.
   * Sandbox build evidence is not required; the artifact is only served when:
   * <ul>
   *   <li>Review completed</li>
   *   <li>Generation is in REVIEWED state</li>
   *   <li>There are zero unresolved findings of any category</li>
   *   <li>The artifact exists and its SHA matches</li>
   * </ul>
   * If any condition fails, a 409 Conflict is returned with a reason.
   */
  @GetMapping("/{id}/download")
  public ResponseEntity<byte[]> download(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    GenerationService.DownloadResult result = generations.prepareDownload(principal.getId(), id);
    if (!result.ready()) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .header("X-Download-Blocked-Reason", result.reason())
          .build();
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.setContentDispositionFormData("attachment", result.filename());
    headers.setContentLength(result.content().length);
    return ResponseEntity.ok().headers(headers).body(result.content());
  }

  /**
   * Request body for the generation rebuild endpoint.
   *
   * @param patchId applied generation patch that should trigger the next
   *     build/verification/review cycle
   */
  public record TriggerRebuildRequest(@jakarta.validation.constraints.NotNull UUID patchId) {}

  /**
   * Optional scope note applied to every FixRequest created by the bulk call.
   */
  public record FixRequestsBulkRequest(String scopeNote) {}

  /**
   * Bulk FixRequest outcome: the reviewed review id, ids of created
   * requests, and the count skipped because an open request already exists.
   */
  public record BulkFixRequestsResponse(UUID reviewId, List<UUID> created, int skippedOpen) {}
}
