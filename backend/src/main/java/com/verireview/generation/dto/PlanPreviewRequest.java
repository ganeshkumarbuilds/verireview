package com.verireview.generation.dto;

import com.verireview.generation.GenerationAuth;
import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationDatabase;
import com.verireview.generation.GenerationFrontend;
import com.verireview.generation.GenerationIntegration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Step 4 (review plan) input. Nothing is persisted and no job is created:
 * the backend only derives the scaffold that the chosen stack would produce.
 */
public record PlanPreviewRequest(
    @NotBlank @Size(max = 20000) String requirement,
    @NotNull GenerationBackend backend,
    @NotNull GenerationFrontend frontend,
    @NotNull GenerationDatabase database,
    GenerationAuth auth,
    @Size(max = 20) List<GenerationIntegration> integrations) {
}
