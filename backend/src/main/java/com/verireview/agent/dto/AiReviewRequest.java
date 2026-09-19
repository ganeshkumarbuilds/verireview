package com.verireview.agent.dto;

import java.util.List;

/**
 * Backend → AI review invocation (mirrors the ai-service
 * {@code ReviewRequest} schema; serialized with snake_case keys).
 */
public record AiReviewRequest(
    String reviewId,
    String projectId,
    String language,
    List<AiFileSnapshot> files,
    List<AiDeterministicFinding> deterministicFindings,
    String idempotencyKey) {
}
