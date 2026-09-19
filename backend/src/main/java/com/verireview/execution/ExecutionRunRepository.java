package com.verireview.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, UUID> {
  List<ExecutionRun> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
  List<ExecutionRun> findByPatchIdOrderByCreatedAtDesc(UUID patchId);
}
