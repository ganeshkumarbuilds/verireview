package com.verireview.generation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Step 4 input: the steps 1–2 context plus the explicit answers. The plan is
 * built only from these values; missing required answers are rejected by the
 * service, never replaced with defaults.
 */
public record GenerationPlanRequest(
    @NotNull @Valid GenerationQuestionsRequest project,
    @Size(max = 50) List<@NotNull @Valid GenerationAnswerRequest> answers) {
}
