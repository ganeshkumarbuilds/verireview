package com.verireview.review;

import com.verireview.common.PagedResponse;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.review.dto.DashboardStatsResponse;
import com.verireview.review.dto.FindingResponse;
import com.verireview.review.dto.ReviewResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Review reads behind the auth boundary. Reviews are project children, so
 * every lookup re-checks project ownership (404 for foreign rows).
 */
@Service
public class ReviewService {

  private final ProjectRepository projects;
  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final ObjectMapper objects;

  public ReviewService(
      ProjectRepository projects, ReviewRepository reviews, FindingRepository findings,
      ObjectMapper objects) {
    this.projects = projects;
    this.reviews = reviews;
    this.findings = findings;
    this.objects = objects;
  }

  @Transactional(readOnly = true)
  public DashboardStatsResponse getDashboardStats(UUID ownerId, UUID projectId) {
    Project project = owned(ownerId, projectId);
    List<com.verireview.review.Review> projectReviews = reviews.findByProjectIdOrderByCreatedAtDesc(project.getId());
    com.verireview.review.Review latestReview = projectReviews.isEmpty() ? null : projectReviews.get(0);
    long totalReviews = projectReviews.size();

    long totalFindings = findings.countByReviewProjectId(project.getId());
    long openFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.OPEN);
    long fixedFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.VERIFIED_FIXED);
    long verifiedFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.VERIFIED_FIXED);
    long rejectedFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.REJECTED);
    long wontfixFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.WONTFIX);
    long criticalHighFindings = findings.countByReviewProjectIdAndSeverityIn(project.getId(), List.of(FindingSeverity.CRITICAL, FindingSeverity.HIGH));
    long deterministicFindings = findings.countByReviewProjectIdAndSource(project.getId(), FindingSource.DETERMINISTIC);
    long aiFindings = findings.countByReviewProjectIdAndSource(project.getId(), FindingSource.AI);

    return new DashboardStatsResponse(
        project.getId(),
        project.getName(),
        totalFindings,
        openFindings,
        fixedFindings,
        verifiedFindings,
        rejectedFindings,
        wontfixFindings,
        criticalHighFindings,
        deterministicFindings,
        aiFindings,
        latestReview != null ? latestReview.getId().hashCode() : 0,
        latestReview != null ? latestReview.getStatus().toString() : "NONE",
        totalReviews
    );
  }

  @Transactional(readOnly = true)
  public List<DashboardStatsResponse> getAllDashboardStats(UUID ownerId) {
    List<Project> userProjects = projects.findByOwnerIdAndDeletedAtIsNull(ownerId);
    return userProjects.stream()
        .map(project -> getDashboardStats(ownerId, project.getId()))
        .toList();
  }

  @Transactional(readOnly = true)
  public PagedResponse<ReviewResponse> history(UUID ownerId, UUID projectId, Pageable pageable) {
    Project project = owned(ownerId, projectId);
    Page<Review> page = reviews.findByProjectIdOrderByCreatedAtDesc(project.getId(), pageable);
    return PagedResponse.of(page.map(ReviewService::toResponse));
  }

  @Transactional(readOnly = true)
  public ReviewResponse get(UUID ownerId, UUID reviewId) {
    return toResponse(ownedReview(ownerId, reviewId));
  }

  @Transactional(readOnly = true)
  public PagedResponse<FindingResponse> findings(
      UUID ownerId, UUID reviewId, FindingSeverity severity, FindingCategory category,
      FindingStatus status, FindingSource source, Boolean toolConfirmed, Pageable pageable) {
    Review review = ownedReview(ownerId, reviewId);
    Page<Finding> page =
        findings.search(review.getId(), severity, category, status, source, toolConfirmed, pageable);
    return PagedResponse.of(page.map(this::toFindingResponseInstance));
  }

  private Project owned(UUID ownerId, UUID projectId) {
    return projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
  }

  private Review ownedReview(UUID ownerId, UUID reviewId) {
    Review review = reviews.findById(reviewId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
    Project project = review.getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found");
    }
    return review;
  }

  static ReviewResponse toResponse(Review review) {
    return new ReviewResponse(
        review.getId(),
        review.getProject().getId(),
        review.getStatus(),
        review.getStartedAt(),
        review.getFinishedAt(),
        review.getDurationMs(),
        review.getFindingCount(),
        review.getError(),
        review.getCreatedAt());
  }

  static FindingResponse toFindingResponse(Finding finding) {
    return toFindingResponse(finding, new ObjectMapper());
  }

  FindingResponse toFindingResponseInstance(Finding finding) {
    return toFindingResponse(finding, objects);
  }

  private static FindingResponse toFindingResponse(Finding finding, ObjectMapper objects) {
    String analyzer = finding.getAnalyzer();
    String rule = finding.getRule();
    try {
      if (finding.getEvidence() != null) {
        JsonNode evidence = objects.readTree(finding.getEvidence());
        if (analyzer == null) {
          analyzer = textOrNull(evidence, "analyzer");
        }
        if (rule == null) {
          rule = textOrNull(evidence, "rule");
        }
      }
    } catch (Exception ignored) {
      // Evidence is best-effort display metadata; the row stays valid.
    }
    return new FindingResponse(
        finding.getId(),
        finding.getReview().getId(),
        finding.getCategory(),
        finding.getSeverity(),
        finding.getSource(),
        finding.getStatus(),
        analyzer,
        rule,
        finding.getTitle(),
        finding.getDescription(),
        finding.getFilePath(),
        finding.getLineStart(),
        finding.getLineEnd(),
        finding.getEvidence(),
        finding.getEvidenceSnippet(),
        finding.getExplanation(),
        finding.getSuggestedFix(),
        finding.getConfidence(),
        finding.isToolConfirmed(),
        finding.getDedupKey(),
        finding.getCreatedAt());
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode child = node.get(field);
    return child == null || child.isNull() ? null : child.asString(null);
  }
}
