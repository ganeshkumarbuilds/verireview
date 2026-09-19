package com.verireview.agent.dto;

/** AI → backend coding proposal (mirrors Python {@code CodingResult}). */
public record AiCodingResult(
    String fixRequestId,
    String agent,
    String promptVersion,
    String diff,
    int filesChanged,
    int additions,
    int deletions,
    String explanation,
    String notes) {
}
