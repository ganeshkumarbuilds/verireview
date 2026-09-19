package com.verireview.review.dto;

import com.verireview.review.ReviewStatus;
import java.time.Instant;
import java.util.UUID;

/** Review execution view with analysis status tracking. */
public record ReviewResponse(
    UUID id,
    UUID projectId,
    ReviewStatus status,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    int findingCount,
    String error,
    Instant createdAt) {
}
