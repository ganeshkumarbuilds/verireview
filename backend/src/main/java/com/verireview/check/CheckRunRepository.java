package com.verireview.check;

import com.verireview.project.Project;
import com.verireview.review.FindingSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for check runs.
 */
@Repository
public interface CheckRunRepository extends JpaRepository<CheckRun, UUID> {

  List<CheckRun> findByProjectIdAndFeatureOrderByCreatedAtDesc(Project project, CheckFeature feature);

  Optional<CheckRun> findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(Project project, CheckFeature feature);

  List<CheckRun> findByProjectIdOrderByCreatedAtDesc(Project project);

  List<CheckRun> findByGenerationIdOrderByCreatedAtDesc(UUID generationId);

  Optional<CheckRun> findFirstByGenerationIdAndFeatureOrderByCreatedAtDesc(UUID generationId, CheckFeature feature);

  @Query("SELECT r FROM CheckRun r WHERE r.status IN ('QUEUED', 'RUNNING') AND r.startedAt IS NOT NULL AND r.startedAt < :threshold")
  List<CheckRun> findStaleRunning(@Param("threshold") Instant threshold);

  List<CheckRun> findByStatusIn(List<CheckStatus> statuses);

  Page<CheckRun> findByProjectId(Project project, Pageable pageable);

  @Query("SELECT COUNT(f) FROM Finding f WHERE f.review.project.id = :projectId AND f.severity = :severity AND f.status = 'OPEN'")
  long countByProjectIdAndSeverity(@Param("projectId") UUID projectId, @Param("severity") FindingSeverity severity);
}