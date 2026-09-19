package com.verireview.fix.dto;

import com.verireview.fix.PatchStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Public patch view. Diff and metadata are read-only; never exposed as entity.
 * Project is derived via fixRequest → finding → review → project for ownership checks.
 */
public record PatchResponse(
    UUID id,
    UUID fixRequestId,
    UUID projectId,
    String diff,
    int filesChanged,
    int additions,
    int deletions,
    PatchStatus status,
    String validationError,
    Instant createdAt,
    Instant updatedAt) {
}
