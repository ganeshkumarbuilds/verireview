package com.verireview.verification.dto;

import com.verireview.verification.BuildStatus;
import com.verireview.verification.VerificationVerdict;
import java.time.Instant;
import java.util.UUID;

public record VerificationRunResponse(
    UUID id,
    UUID patchId,
    UUID executionRunId,
    BuildStatus buildStatus,
    int testsTotal,
    int testsPassed,
    int testsFailed,
    int testsSkipped,
    VerificationVerdict verdict,
    String logRef,
    Long durationMs,
    Instant createdAt,
    Instant updatedAt) {}
