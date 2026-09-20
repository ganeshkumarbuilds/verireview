package com.verireview.generation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationArtifactRepository extends JpaRepository<GenerationArtifact, UUID> {

  Optional<GenerationArtifact> findFirstByGenerationIdOrderByCreatedAtDesc(UUID generationId);

  List<GenerationArtifact> findByGenerationIdOrderByCreatedAtAsc(UUID generationId);
}
