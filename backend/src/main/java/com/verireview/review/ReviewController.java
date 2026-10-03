package com.verireview.review;

import com.verireview.analysis.AnalysisJobService;
import com.verireview.common.PagedResponse;
import com.verireview.review.dto.DashboardStatsResponse;
import com.verireview.review.dto.FindingResponse;
import com.verireview.review.dto.ReviewResponse;
import com.verireview.security.VeriReviewUserDetails;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deterministic analysis boundary (Phase 6). Triggering is async (202 with
 * the QUEUED review); clients poll the review until COMPLETED/FAILED.
 * Findings here are {@code DETERMINISTIC} only — AI verdicts never appear
 * (AGENTS.md rules 10–11).
 */
@RestController
public class ReviewController {

  private final AnalysisJobService jobs;
  private final ReviewService reviews;
  private final ReviewRepository reviewRepository;
  private final FindingRepository findingRepository;

  public ReviewController(
      AnalysisJobService jobs,
      ReviewService reviews,
      ReviewRepository reviewRepository,
      FindingRepository findingRepository) {
    this.jobs = jobs;
    this.reviews = reviews;
    this.reviewRepository = reviewRepository;
    this.findingRepository = findingRepository;
  }

  @PostMapping("/api/v1/projects/{id}/analysis")
  public ResponseEntity<ReviewResponse> analyze(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID projectId) {
    Review review = jobs.trigger(principal.getId(), projectId);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ReviewService.toResponse(review));
  }

  @GetMapping("/api/v1/projects/{id}/reviews")
  public ResponseEntity<PagedResponse<ReviewResponse>> history(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID projectId,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return ResponseEntity.ok(reviews.history(principal.getId(), projectId, pageable));
  }

  @GetMapping("/api/v1/reviews/{id}")
  public ResponseEntity<ReviewResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID reviewId) {
    return ResponseEntity.ok(reviews.get(principal.getId(), reviewId));
  }

  @GetMapping("/api/v1/reviews/{id}/findings")
  public ResponseEntity<PagedResponse<FindingResponse>> findings(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID reviewId,
      @RequestParam(value = "severity", required = false) FindingSeverity severity,
      @RequestParam(value = "category", required = false) FindingCategory category,
      @RequestParam(value = "status", required = false) FindingStatus status,
      @PageableDefault(size = 50) Pageable pageable) {
    return ResponseEntity.ok(
        reviews.findings(principal.getId(), reviewId, severity, category, status, pageable));
  }

  /**
   * Dashboard statistics for a project: findings breakdown by status/source/severity,
   * plus latest review info. Owner-scoped (404 for non-owners).
   */
  @GetMapping("/api/v1/projects/{id}/dashboard-stats")
  public ResponseEntity<DashboardStatsResponse> dashboardStats(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID projectId) {
    return ResponseEntity.ok(reviews.getDashboardStats(principal.getId(), projectId));
  }

  /**
   * Aggregated dashboard statistics across all user projects.
   */
  @GetMapping("/api/v1/dashboard/stats")
  public ResponseEntity<List<DashboardStatsResponse>> allDashboardStats(
      @AuthenticationPrincipal VeriReviewUserDetails principal) {
    return ResponseEntity.ok(reviews.getAllDashboardStats(principal.getId()));
  }
}
