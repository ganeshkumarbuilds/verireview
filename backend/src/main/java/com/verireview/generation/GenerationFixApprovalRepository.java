package com.verireview.generation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationFixApprovalRepository extends JpaRepository<GenerationFixApproval, UUID> {
  List<GenerationFixApproval> findByGenerationIdOrderByRoundNumberAscCreatedAtAsc(UUID generationId);
  Optional<GenerationFixApproval> findByIdAndGenerationIdAndUserId(
      UUID id, UUID generationId, UUID userId);
}
