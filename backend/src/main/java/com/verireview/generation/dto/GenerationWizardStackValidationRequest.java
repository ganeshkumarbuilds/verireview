package com.verireview.generation.dto;

import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationFrontend;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GenerationWizardStackValidationRequest(
    @NotBlank String engine,
    @NotNull GenerationBackend backend,
    @NotNull GenerationFrontend frontend) {
}
