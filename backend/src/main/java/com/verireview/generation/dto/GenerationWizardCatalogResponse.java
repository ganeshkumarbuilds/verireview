package com.verireview.generation.dto;

import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationFrontend;
import java.util.List;

public record GenerationWizardCatalogResponse(List<Engine> engines) {

  public record Engine(
      String id,
      String label,
      String description,
      List<GenerationBackend> supportedBackends,
      List<GenerationFrontend> supportedFrontends) {
  }
}
