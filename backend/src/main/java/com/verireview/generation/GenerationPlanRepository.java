package com.verireview.generation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationPlanRepository extends JpaRepository<GenerationPlan, UUID> {

  Optional<GenerationPlan> findFirstByGenerationIdOrderByIterationDesc(UUID generationId);

  Optional<GenerationPlan> findByGenerationIdAndIteration(UUID generationId, int iteration);
}
