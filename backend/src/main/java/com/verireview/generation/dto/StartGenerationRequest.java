package com.verireview.generation.dto;

import jakarta.validation.constraints.Size;

/**
 * Secrets supplied when starting a draft. Accepted in the request body only,
 * held in memory for the run, never persisted, logged, or returned.
 * Until secure secret storage exists, drafts hold no secrets server-side —
 * starting always requires re-supplying them here.
 */
public record StartGenerationRequest(
    @Size(max = 500) String password,
    @Size(max = 2000) String apiKey) {
}
