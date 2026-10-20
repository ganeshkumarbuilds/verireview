package com.verireview.generation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRestructureRepository
    extends JpaRepository<GenerationRestructure, UUID> {

  List<GenerationRestructure> findByGenerationIdOrderByCreatedAtDesc(UUID generationId);

  Optional<GenerationRestructure> findFirstByGenerationIdAndStatusOrderByCreatedAtDesc(
      UUID generationId, GenerationRestructure.Status status);

  Optional<GenerationRestructure> findByIdAndGenerationId(UUID id, UUID generationId);
}
