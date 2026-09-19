package com.verireview.project.dto;

import java.util.UUID;

/** File inventory row. Content itself is served via the content endpoint. */
public record ProjectFileResponse(
    UUID id,
    String path,
    String language,
    long sizeBytes,
    String sha256) {
}
