package com.verireview.verification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRunRepository extends JpaRepository<VerificationRun, UUID> {
  List<VerificationRun> findByPatchIdOrderByCreatedAtDesc(UUID patchId);
  List<VerificationRun> findByExecutionRunIdOrderByCreatedAtDesc(UUID executionRunId);
  List<VerificationRun> findByGenerationIdOrderByCreatedAtDesc(UUID generationId);
}
