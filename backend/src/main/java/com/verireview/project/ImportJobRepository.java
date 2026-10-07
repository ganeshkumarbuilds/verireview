package com.verireview.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

  Optional<ImportJob> findByIdAndOwnerId(UUID id, UUID ownerId);

  @Query("""
      SELECT j FROM ImportJob j
      WHERE j.owner.id = :ownerId
      ORDER BY j.createdAt DESC
      """)
  Page<ImportJob> findByOwnerId(UUID ownerId, Pageable pageable);

  List<ImportJob> findByOwnerIdAndStatusIn(UUID ownerId, List<ImportJobStatus> statuses);

  long countByOwnerIdAndStatusIn(UUID ownerId, List<ImportJobStatus> statuses);
}