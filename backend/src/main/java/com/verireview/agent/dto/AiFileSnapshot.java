package com.verireview.agent.dto;

/**
 * One scoped source file sent to the AI service (AGENT_DESIGN least-context:
 * capped, truncated with an omission marker, backend-normalized paths).
 */
public record AiFileSnapshot(
    String path,
    String language,
    String content,
    boolean truncated) {
}
