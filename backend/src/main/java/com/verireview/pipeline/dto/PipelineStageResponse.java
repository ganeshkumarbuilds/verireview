package com.verireview.pipeline.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One stage of the visible agent pipeline (Import -> Analyze -> Review -> Fix -> Verify).
 *
 * <p>Every field is derived from real backend records (project ingestion, reviews,
 * agent executions, check runs/steps, execution evidence). Nothing here is a
 * hardcoded placeholder state.
 *
 * @param key         stable machine key: import|analyze|review|fix|verify
 * @param label       human label shown to the user
 * @param state       WAITING | RUNNING | COMPLETED | FAILED | SKIPPED
 * @param startedAt   real start time, or null when the stage has not run
 * @param finishedAt  real end time, or null while running/waiting
 * @param durationMs  real duration, or null when unknown
 * @param detail      short plain-language summary of what happened
 * @param error       plain-language failure reason, never a stack trace
 * @param checkRunId  the backing check run id when the stage is a check run
 */
public record PipelineStageResponse(
    String key,
    String label,
    String state,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String detail,
    String error,
    UUID checkRunId) {}
