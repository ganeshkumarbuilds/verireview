package com.verireview.generation;

import com.verireview.common.PagedResponse;
import com.verireview.generation.dto.CreateGenerationRequest;
import com.verireview.generation.dto.CreateGenerationRevisionRequest;
import com.verireview.generation.dto.GenerationResponse;
import com.verireview.generation.dto.GenerationRevisionResponse;
import com.verireview.generation.dto.StartGenerationRequest;
import com.verireview.generation.dto.UpdateGenerationRequest;
import com.verireview.security.VeriReviewUserDetails;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generation boundary. Thin by rule — validation, ownership, secrets
 * handling, and audit live in {@link GenerationService}. The create call
 * only queues the job; the frontend polls {@code GET /{id}} for the real
 * backend state (QUEUED → … → COMPLETED / FAILED).
 */
@RestController
@RequestMapping("/api/v1/generations")
public class GenerationController {

  private final GenerationService generations;

  public GenerationController(GenerationService generations) {
    this.generations = generations;
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
      @PageableDefault(size = 20, sort = "createdAt",
          direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
    return ResponseEntity.ok(generations.list(principal.getId(), pageable));
  }

  @GetMapping("/{id}")
  public ResponseEntity<GenerationResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(generations.get(principal.getId(), id));
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
}
