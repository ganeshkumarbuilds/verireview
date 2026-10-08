package com.verireview.check;

import com.verireview.project.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for project health scores.
 */
@Repository
public interface ProjectHealthScoreRepository extends JpaRepository<ProjectHealthScore, UUID> {

  Optional<ProjectHealthScore> findFirstByProjectIdOrderByLastComputedAtDesc(Project project);

  List<ProjectHealthScore> findByProjectIdOrderByLastComputedAtDesc(Project project);
}