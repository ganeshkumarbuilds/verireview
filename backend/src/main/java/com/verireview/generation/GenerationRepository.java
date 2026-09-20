package com.verireview.generation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRepository extends JpaRepository<Generation, UUID> {

  Optional<Generation> findByIdAndOwnerId(UUID id, UUID ownerId);

  boolean existsByOwnerIdAndName(UUID ownerId, String name);

  Page<Generation> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable);

  Optional<Generation> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
