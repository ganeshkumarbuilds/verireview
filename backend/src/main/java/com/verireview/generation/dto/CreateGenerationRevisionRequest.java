package com.verireview.generation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Appended task modification. Carries the full new requirement text (the
 * backend versions it) and nothing else — no secrets, no stack changes.
 */
public record CreateGenerationRevisionRequest(
    @NotBlank @Size(max = 20000) String requirement) {
}
