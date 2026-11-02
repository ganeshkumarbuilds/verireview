package com.verireview.generation.dto;

import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationFrontend;
import java.util.List;

public record GenerationWizardCatalogResponse(
    List<BackendOption> backends,
    List<FrontendOption> frontends) {

  public record BackendOption(
      GenerationBackend id,
      String label,
      String description) {
  }

  public record FrontendOption(
      GenerationFrontend id,
      String label,
      String description) {
  }
}
