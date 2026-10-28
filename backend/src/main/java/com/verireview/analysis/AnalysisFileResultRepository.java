package com.verireview.analysis;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisFileResultRepository extends JpaRepository<AnalysisFileResult, UUID> {

  void deleteByReviewId(UUID reviewId);

  List<AnalysisFileResult> findByReviewIdOrderByPathAsc(UUID reviewId);
}
