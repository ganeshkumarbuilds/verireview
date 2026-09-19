package com.verireview.project;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

  /** Ownership predicate: soft-deleted or foreign rows behave as missing. */
  Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  boolean existsByOwnerIdAndNameAndDeletedAtIsNull(UUID ownerId, String name);

  @Query("""
      SELECT p FROM Project p
      WHERE p.owner.id = :ownerId AND p.deletedAt IS NULL
        AND LOWER(p.name) LIKE LOWER(:namePattern) ESCAPE '\\'
        AND (:sourceType IS NULL OR p.sourceType = :sourceType)
      """)
  Page<Project> search(UUID ownerId, String namePattern, ProjectSourceType sourceType,
      Pageable pageable);
}
