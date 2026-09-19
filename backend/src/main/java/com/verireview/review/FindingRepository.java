package com.verireview.review;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

  boolean existsByReviewIdAndDedupKey(UUID reviewId, String dedupKey);

  @Query("""
      SELECT f FROM Finding f
      WHERE f.review.id = :reviewId
        AND (:severity IS NULL OR f.severity = :severity)
        AND (:category IS NULL OR f.category = :category)
        AND (:status IS NULL OR f.status = :status)
      """)
  Page<Finding> search(UUID reviewId, FindingSeverity severity, FindingCategory category,
      FindingStatus status, Pageable pageable);
}
