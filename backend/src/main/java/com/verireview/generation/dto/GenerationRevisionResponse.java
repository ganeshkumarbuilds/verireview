package com.verireview.generation.dto;

import java.time.Instant;
import java.util.UUID;

/** One version of a generation's task. Task text only — never secrets. */
public record GenerationRevisionResponse(
    UUID id,
    UUID generationId,
    int revisionNumber,
    String requirement,
    Instant createdAt) {
}
