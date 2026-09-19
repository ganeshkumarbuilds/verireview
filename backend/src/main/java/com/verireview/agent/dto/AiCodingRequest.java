package com.verireview.agent.dto;

import java.util.List;

/**
 * Backend → AI coding invocation (mirrors Python {@code CodingRequest}).
 * Serialized with snake_case keys.
 */
public record AiCodingRequest(
    String fixRequestId,
    AiCodingFinding finding,
    String scopeNote,
    String language,
    List<AiFileSnapshot> files,
    String idempotencyKey) {
}
