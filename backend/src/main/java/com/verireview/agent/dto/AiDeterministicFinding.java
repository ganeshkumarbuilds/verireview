package com.verireview.agent.dto;

/** One deterministic tool hit, restated for the AI service contract. */
public record AiDeterministicFinding(
    String tool,
    String ruleId,
    String file,
    Integer line,
    String message) {
}
