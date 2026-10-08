package com.verireview.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

  boolean existsByReviewIdAndDedupKey(UUID reviewId, String dedupKey);

  List<Finding> findByReviewId(UUID reviewId);

  @Query("""
      SELECT f FROM Finding f
      WHERE f.review.id = :reviewId
        AND (:severity IS NULL OR f.severity = :severity)
        AND (:category IS NULL OR f.category = :category)
        AND (:status IS NULL OR f.status = :status)
        AND (:source IS NULL OR f.source = :source)
        AND (:toolConfirmed IS NULL OR f.toolConfirmed = :toolConfirmed)
      """)
  Page<Finding> search(UUID reviewId, FindingSeverity severity, FindingCategory category,
      FindingStatus status, FindingSource source, Boolean toolConfirmed, Pageable pageable);

  List<Finding> findByReviewProjectIdAndSeverityInAndCreatedAtAfter(
      UUID projectId, List<FindingSeverity> severities, Instant after);

  boolean existsByReviewIdAndCategoryInAndStatus(UUID reviewId, List<FindingCategory> categories,
      FindingStatus status);

  // Dashboard stats queries
  long countByReviewProjectId(UUID projectId);

  long countByReviewProjectIdAndStatus(UUID projectId, FindingStatus status);

  long countByReviewProjectIdAndSeverityIn(UUID projectId, List<FindingSeverity> severities);

  long countByReviewProjectIdAndSource(UUID projectId, FindingSource source);

  long countByReviewProjectIdAndToolConfirmed(UUID projectId, boolean toolConfirmed);

  // Dedupe across re-runs
  @Query("""
      SELECT f FROM Finding f
      WHERE f.dedupKey = :dedupKey
        AND f.review.project.id = :projectId
      ORDER BY f.createdAt DESC
      """)
  List<Finding> findByDedupKeyAndProjectId(String dedupKey, UUID projectId);

  // For FIX: find findings by severity for bulk fix
  @Query("""
      SELECT f FROM Finding f
      WHERE f.review.project.id = :projectId
        AND f.status = 'OPEN'
        AND f.severity = :severity
      """)
  List<Finding> findOpenByProjectIdAndSeverity(UUID projectId, FindingSeverity severity);
}
