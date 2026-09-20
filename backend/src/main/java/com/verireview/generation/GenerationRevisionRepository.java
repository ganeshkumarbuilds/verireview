package com.verireview.generation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRevisionRepository extends JpaRepository<GenerationRevision, UUID> {

  Optional<GenerationRevision> findFirstByGenerationIdOrderByRevisionNumberDesc(UUID generationId);

  List<GenerationRevision> findByGenerationIdOrderByRevisionNumberAsc(UUID generationId);

  long countByGenerationId(UUID generationId);
}
