package com.verireview.analysis;

import com.verireview.audit.AuditService;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.review.Finding;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSource;
import com.verireview.review.FindingStatus;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deterministic analysis jobs (Phase 6): trigger creates a QUEUED review and
 * hands execution to {@link AnalysisRunner}; these methods are the short
 * transactional boundaries (never the minutes-long sandbox run itself). AI
 * stays out of this path — the Review Agent consumes the schema in Phase 7.
 */
@Service
public class AnalysisJobService {

  private final ProjectRepository projects;
  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final AnalysisRunner runner;
  private final AuditService audits;

  public AnalysisJobService(
      ProjectRepository projects,
      ReviewRepository reviews,
      FindingRepository findings,
      AnalysisRunner runner,
      AuditService audits) {
    this.projects = projects;
    this.reviews = reviews;
    this.findings = findings;
    this.runner = runner;
    this.audits = audits;
  }

  /** Creates the QUEUED review and hands execution to the async worker. */
  @Transactional
  public Review trigger(UUID ownerId, UUID projectId) {
    Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    List<ReviewStatus> busy = List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING);
    if (reviews.existsByProjectIdAndStatusIn(project.getId(), busy)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "An analysis is already running");
    }
    Review review = new Review(project);
    reviews.save(review);
    audits.record(project.getOwner(), "ANALYSIS_STARTED", "review", review.getId().toString());
    runner.runAsync(review.getId());
    return review;
  }

  /** Detached snapshot for the worker (no lazy loading outside transactions). */
  @Transactional(readOnly = true)
  public ReviewSnapshot snapshot(UUID reviewId) {
    Review review = reviews.findById(reviewId).orElse(null);
    if (review == null) {
      return null;
    }
    Project project = review.getProject();
    return new ReviewSnapshot(review.getId(), project.getId(), project.getStorageRef(),
        project.getOwner().getId());
  }

  public record ReviewSnapshot(
      UUID reviewId, UUID projectId, String storageRef, UUID ownerId) {
  }

  @Transactional
  public void markRunning(UUID reviewId, Instant started) {
    Review managed = reviews.findById(reviewId).orElseThrow();
    managed.setStatus(ReviewStatus.RUNNING);
    managed.setStartedAt(started);
  }

  @Transactional
  public void finish(
      UUID reviewId, Instant started, ReviewStatus status, int findingCount, String error) {
    Review managed = reviews.findById(reviewId).orElseThrow();
    Instant finished = Instant.now();
    managed.setStatus(status);
    if (managed.getStartedAt() == null) {
      managed.setStartedAt(started);
    }
    managed.setFinishedAt(finished);
    managed.setDurationMs(Duration.between(started, finished).toMillis());
    managed.setFindingCount(findingCount);
    managed.setError(error);
  }

  @Transactional
  public int persistFindings(UUID reviewId, List<ToolReport> reports) {
    Review managed = reviews.findById(reviewId).orElseThrow();
    Set<String> seen = new LinkedHashSet<>();
    List<Finding> rows = new ArrayList<>();
    for (ToolReport report : reports) {
      for (NormalizedFinding hit : report.findings()) {
        String key = Fingerprint.of(hit.analyzer(), hit.rule(), hit.filePath(),
            hit.lineStart(), hit.description());
        if (!seen.add(key)
            || findings.existsByReviewIdAndDedupKey(managed.getId(), key)) {
          continue;
        }
        Finding row = new Finding(managed, hit.category(), hit.severity(),
            FindingSource.DETERMINISTIC, hit.title());
        row.setDescription(hit.description());
        row.setFilePath(hit.filePath());
        row.setLineStart(hit.lineStart());
        row.setLineEnd(hit.lineEnd());
        row.setEvidence(hit.evidenceJson());
        row.setDedupKey(key);
        row.setStatus(FindingStatus.OPEN);
        rows.add(row);
      }
    }
    findings.saveAll(rows);
    return rows.size();
  }
}
