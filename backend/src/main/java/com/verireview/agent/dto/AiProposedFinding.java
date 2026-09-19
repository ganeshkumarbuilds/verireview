package com.verireview.agent.dto;

/** One AI-proposed finding, as returned by the AI service. */
public record AiProposedFinding(
    String category,
    String severity,
    String title,
    String description,
    String filePath,
    Integer lineStart,
    Integer lineEnd,
    String evidence,
    String source,
    String suggestedFixHint,
    Double confidence) {
}
