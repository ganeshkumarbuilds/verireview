package com.verireview.generation.dto;

import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationFrontend;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Step 3 input: the answers to steps 1–2 that determine the clarifying
 * questions. No database, auth, or integration choice is accepted here — the
 * backend asks for those so they are never silently defaulted.
 */
public record GenerationQuestionsRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(min = 20, max = 20000) String requirement,
    @Size(max = 5000) String description,
    @NotNull GenerationBackend backend,
    @NotNull GenerationFrontend frontend,
    @NotBlank @Size(max = 200) String languageRuntime,
    @Size(max = 200) String backendCustom,
    @Size(max = 200) String frontendCustom) {
}
