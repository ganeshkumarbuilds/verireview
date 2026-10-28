package com.verireview.analysis;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisCheckRepository extends JpaRepository<AnalysisCheck, UUID> {
  List<AnalysisCheck> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);
  void deleteByReviewId(UUID reviewId);
}
