package com.verireview.project.dto;

/** Capped, read-only file view (SECURITY_DESIGN §3). */
public record FileContentResponse(
    String path,
    long sizeBytes,
    boolean truncated,
    String content) {
}
