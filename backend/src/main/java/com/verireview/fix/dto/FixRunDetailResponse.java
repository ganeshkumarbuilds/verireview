package com.verireview.fix.dto;

import com.verireview.check.dto.CheckStepResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full detail of a fix run and its automatic re-check (VERIFY).
 *
 * @param status              QUEUED | RUNNING | SUCCEEDED | FAILED | SKIPPED
 * @param verificationVerdict VERIFIED | REJECTED | PENDING | null
 * @param sandboxSkipped      true when the sandbox was unavailable so nothing was actually verified
 */
public record FixRunDetailResponse(
    UUID checkRunId,
    UUID projectId,
    String status,
    String error,
    List<CheckStepResponse> steps,
    FixRunSummaryResponse summary,
    List<FixRunChangedFileResponse> changedFiles,
    String verificationVerdict,
    boolean sandboxSkipped,
    Instant startedAt,
    Instant finishedAt) {}
