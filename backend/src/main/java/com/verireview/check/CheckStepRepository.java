package com.verireview.check;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for check steps.
 */
@Repository
public interface CheckStepRepository extends JpaRepository<CheckStep, UUID> {

  List<CheckStep> findByCheckRunIdOrderByStepOrderAsc(UUID checkRunId);

  Optional<CheckStep> findByCheckRunIdAndStepOrder(UUID checkRunId, int stepOrder);
}