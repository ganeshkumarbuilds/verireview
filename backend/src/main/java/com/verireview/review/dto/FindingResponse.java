package com.verireview.review.dto;

import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.FindingStatus;
import java.time.Instant;
import java.util.UUID;

/** Normalized deterministic finding (roadmap schema frozen for Phase 7). */
public record FindingResponse(
    UUID id,
    UUID reviewId,
    FindingCategory category,
    FindingSeverity severity,
    FindingSource source,
    FindingStatus status,
    String analyzer,
    String rule,
    String title,
    String description,
    String filePath,
    Integer lineStart,
    Integer lineEnd,
    String evidence,
    String dedupKey,
    Instant createdAt) {
}
