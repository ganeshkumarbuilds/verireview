package com.verireview.review;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

  boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<ReviewStatus> statuses);

  Page<Review> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);

  List<Review> findByGenerationIdOrderByCreatedAtDesc(UUID generationId);
}
