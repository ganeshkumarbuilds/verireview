package com.verireview.analysis;

import com.verireview.audit.AuditService;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectFileRepository;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSource;
import com.verireview.review.FindingStatus;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import com.verireview.review.dto.StartReviewRequest;
import com.verireview.review.dto.RetryReviewRequest;
import com.verireview.review.ReviewEventStream;
import com.verireview.generation.GenerationStaticAnalyzer;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
  private final AnalysisCheckRepository checks;
  private final AnalysisFileResultRepository fileResults;
  private final ProjectFileRepository projectFiles;
  private final AnalysisRunner runner;
  private final AuditService audits;
  private final ReviewEventStream events;

  public AnalysisJobService(
      ProjectRepository projects,
      ReviewRepository reviews,
      FindingRepository findings,
      AnalysisCheckRepository checks,
      AnalysisFileResultRepository fileResults,
      ProjectFileRepository projectFiles,
      AnalysisRunner runner,
      AuditService audits,
      ReviewEventStream events) {
    this.projects = projects;
    this.reviews = reviews;
    this.findings = findings;
    this.checks = checks;
    this.fileResults = fileResults;
    this.projectFiles = projectFiles;
    this.runner = runner;
    this.audits = audits;
    this.events = events;
  }

  @Transactional
  public void retry(Review review, RetryReviewRequest request) {
    review.setStatus(ReviewStatus.QUEUED);
    review.setError(null);
    review.setFinishedAt(null);
    review.setDurationMs(null);
    review.setFindingCount(0);
    ReviewCredentials credentials = new ReviewCredentials(
        normalize(request.provider(), "OPENROUTER"),
        normalize(request.model(), ""),
        request.apiKey() == null ? "" : request.apiKey().trim());
    reviews.save(review);
    dispatchAfterCommit(review.getId(), credentials);
  }

  /** Creates the QUEUED review and hands execution to the async worker. */
  @Transactional
  public Review trigger(UUID ownerId, UUID projectId) {
    return trigger(ownerId, projectId, null);
  }

  @Transactional
  public Review trigger(UUID ownerId, UUID projectId, StartReviewRequest request) {
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
    ReviewCredentials credentials = request == null
        ? ReviewCredentials.none()
        : new ReviewCredentials(
            normalize(request.provider(), "OPENROUTER"),
            normalize(request.model(), ""),
            request.apiKey() == null ? "" : request.apiKey().trim());
    dispatchAfterCommit(review.getId(), credentials);
    return review;
  }

  public record ReviewCredentials(String provider, String model, String apiKey) {
    public static ReviewCredentials none() {
      return new ReviewCredentials("OPENROUTER", "", "");
    }
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  /**
   * Hands the job to the async runner only after this creation transaction
   * commits. Dispatching inline would let the worker's {@link #snapshot(UUID)}
   * read race the commit, miss the row, and return early -- stranding the
   * review in QUEUED forever with no error anywhere. Same guard the
   * generation runner already uses.
   */
  private void dispatchAfterCommit(UUID reviewId, ReviewCredentials credentials) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          runner.runAsync(reviewId, credentials);
        }
      });
    } else {
      runner.runAsync(reviewId, credentials);
    }
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
    fileResults.deleteByReviewId(reviewId);
    events.publish(reviewId);
  }

  @Transactional
  public void recordFileResult(
      UUID reviewId, String path, AnalysisFileStatus status, String reason,
      long sizeBytes, int findingCount) {
    Review review = reviews.findById(reviewId).orElseThrow();
    fileResults.save(new AnalysisFileResult(
        review, path, status, reason, sizeBytes, findingCount));
    events.publish(reviewId);
  }

  @Transactional
  public int persistStaticFindings(
      UUID reviewId, List<GenerationStaticAnalyzer.StaticFinding> staticFindings) {
    Review review = reviews.findById(reviewId).orElseThrow();
    List<Finding> rows = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (GenerationStaticAnalyzer.StaticFinding hit : staticFindings) {
      FindingCategory category = switch (hit.category().toLowerCase()) {
        case "syntax" -> FindingCategory.SYNTAX;
        case "logic" -> FindingCategory.LOGIC;
        case "security" -> FindingCategory.SECURITY;
        case "style" -> FindingCategory.STYLE;
        default -> FindingCategory.CODE_QUALITY;
      };
      String key = Fingerprint.of(
          "generation-static", hit.title(), hit.file(), hit.line(), hit.explanation());
      if (!seen.add(key) || findings.existsByReviewIdAndDedupKey(reviewId, key)) {
        continue;
      }
      Finding row = new Finding(
          review, category, hit.severity(), FindingSource.DETERMINISTIC, hit.title());
      row.setDescription(hit.explanation());
      row.setExplanation(hit.explanation());
      row.setSuggestedFix(hit.explanation());
      row.setFilePath(hit.file());
      row.setLineStart(hit.line());
      row.setLineEnd(hit.line());
      row.setAnalyzer("generation-static");
      row.setRule("generation-static:" + hit.category());
      row.setDedupKey(key);
      row.setStatus(FindingStatus.OPEN);
      row.setToolConfirmed(true);
      rows.add(row);
    }
    findings.saveAll(rows);
    return rows.size();
  }

  @Transactional
  public void finish(
      UUID reviewId, Instant started, ReviewStatus status, int findingCount, String error) {
    finish(reviewId, started, status, findingCount, error, List.of());
  }

  @Transactional
  public void finish(
      UUID reviewId, Instant started, ReviewStatus status, int findingCount, String error,
      List<ToolReport> reports) {
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
    if (!reports.isEmpty()) {
      checks.deleteByReviewId(managed.getId());
      checks.saveAll(reports.stream().map(report -> new AnalysisCheck(
          managed,
          report.analyzer(),
          report.status() == ToolReport.ToolStatus.RAN
              ? AnalysisCheckStatus.RAN
              : report.status() == ToolReport.ToolStatus.FAILED
                ? AnalysisCheckStatus.FAILED
                : AnalysisCheckStatus.SKIPPED,
          report.detail(),
          report.durationMs(),
          report.status() == ToolReport.ToolStatus.RAN ? countFiles(managed) : 0,
          report.findings().size())).toList());
    }
    events.publish(reviewId);
  }

  private int countFiles(Review review) {
    return (int) projectFiles.countByProjectId(review.getProject().getId());
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
