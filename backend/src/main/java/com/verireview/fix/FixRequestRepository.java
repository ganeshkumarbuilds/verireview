package com.verireview.fix;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FixRequestRepository extends JpaRepository<FixRequest, UUID> {

  List<FixRequest> findByFindingIdOrderByCreatedAtDesc(UUID findingId);

  boolean existsByFindingIdAndStatusIn(UUID findingId, Collection<FixRequestStatus> statuses);
}
