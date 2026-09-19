package com.verireview.fix.dto;

import com.verireview.fix.FixRequestStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Public fix-request view. Carries finding/project/user ids (never entities).
 * The project is resolved through finding → review → project.
 */
public record FixRequestResponse(
    UUID id,
    UUID findingId,
    UUID projectId,
    UUID requestedBy,
    FixRequestStatus status,
    String scopeNote,
    String error,
    Instant createdAt,
    Instant updatedAt) {
}
