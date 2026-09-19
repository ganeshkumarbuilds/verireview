package com.verireview.execution.dto;

import com.verireview.execution.ExecutionStatus;
import com.verireview.verification.BuildStatus;
import java.time.Instant;
import java.util.UUID;

public record ExecutionRunResponse(
    UUID id,
    UUID projectId,
    UUID patchId,
    ExecutionStatus status,
    Integer exitCode,
    String stdout,
    String stderr,
    Long durationMs,
    BuildStatus buildStatus,
    Instant createdAt,
    Instant updatedAt) {}
