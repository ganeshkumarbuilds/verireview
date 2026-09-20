package com.verireview.generation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenerationExecutionEvidenceRepository
    extends JpaRepository<GenerationExecutionEvidence, UUID> {

  Optional<GenerationExecutionEvidence> findByGenerationIdAndIteration(UUID generationId, int iteration);
}