package com.verireview.generation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationFileVersionRepository extends JpaRepository<GenerationFileVersion, UUID> {
  List<GenerationFileVersion> findByGenerationIdOrderByRoundNumberAscFilePathAsc(UUID generationId);
  List<GenerationFileVersion> findByGenerationIdAndRoundNumberOrderByFilePathAsc(
      UUID generationId, int roundNumber);
}
