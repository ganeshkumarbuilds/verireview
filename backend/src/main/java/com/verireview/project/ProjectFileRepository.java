package com.verireview.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, UUID> {

  long countByProjectId(UUID projectId);

  List<ProjectFile> findByProjectId(UUID projectId);

  @Query("""
      SELECT f FROM ProjectFile f
      WHERE f.project.id = :projectId
        AND f.path LIKE CONCAT(:prefix, '%') ESCAPE '\\'
        AND LOWER(f.path) LIKE LOWER(:searchPattern) ESCAPE '\\'
      """)
  Page<ProjectFile> search(UUID projectId, String prefix, String searchPattern, Pageable pageable);
}
