package com.verireview.project.dto;

import com.verireview.project.ProjectSourceType;
import com.verireview.project.ProjectStatus;
import java.time.Instant;
import java.util.UUID;

/** Public project view. The server-side storage path never leaves the API. */
public record ProjectResponse(
    UUID id,
    String name,
    String description,
    ProjectSourceType sourceType,
    String language,
    ProjectStatus status,
    long fileCount,
    Instant createdAt,
    Instant updatedAt) {
}
