package com.verireview.agent.dto;

import java.util.List;

/**
 * AI → backend review proposal (mirrors the ai-service
 * {@code ReviewResult} schema).
 */
public record AiReviewResult(
    String reviewId,
    String agent,
    String promptVersion,
    List<AiProposedFinding> findings,
    String notes,
    int invalidItems) {
}
