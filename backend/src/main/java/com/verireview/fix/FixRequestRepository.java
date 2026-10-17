package com.verireview.fix;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FixRequestRepository extends JpaRepository<FixRequest, UUID> {

  List<FixRequest> findByFindingIdOrderByCreatedAtDesc(UUID findingId);

  boolean existsByFindingIdAndStatusIn(UUID findingId, Collection<FixRequestStatus> statuses);

  List<FixRequest> findByFindingIdAndStatusIn(UUID findingId, Collection<FixRequestStatus> statuses);

  @Query("SELECT COUNT(fr) FROM FixRequest fr JOIN fr.finding f JOIN f.review r WHERE r.project.id = :projectId AND fr.status = :status")
  long countByFindingReviewProjectIdAndStatus(UUID projectId, FixRequestStatus status);
}