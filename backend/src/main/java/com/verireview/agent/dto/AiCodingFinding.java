package com.verireview.agent.dto;

/** Finding context sent to the AI coding service (least-context). */
public record AiCodingFinding(
    String id,
    String title,
    String description,
    String filePath,
    Integer lineStart,
    Integer lineEnd,
    String category,
    String severity,
    String evidence) {
}
