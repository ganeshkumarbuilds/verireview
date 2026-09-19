package com.verireview.fix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatchRepository extends JpaRepository<Patch, UUID> {
  List<Patch> findByFixRequestIdOrderByCreatedAtDesc(UUID fixRequestId);

  Optional<Patch> findTopByFixRequestIdOrderByCreatedAtDesc(UUID fixRequestId);
}
