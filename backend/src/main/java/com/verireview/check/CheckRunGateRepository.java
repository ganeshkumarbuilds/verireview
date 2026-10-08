package com.verireview.check;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for check run gates (VERIFY feature).
 */
@Repository
public interface CheckRunGateRepository extends JpaRepository<CheckRunGate, UUID> {

  List<CheckRunGate> findByCheckRunIdOrderByCreatedAtAsc(UUID checkRunId);

  Optional<CheckRunGate> findByCheckRunIdAndGateName(UUID checkRunId, String gateName);
}