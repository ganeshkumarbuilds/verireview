package com.verireview.project.dto;

import com.verireview.project.ProjectSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Manual project shell (API_DESIGN §2). The source type is provisional until
 *  the first ingestion fills the shell; imports set it explicitly. */
public record CreateProjectRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 5000) String description,
    @Size(max = 50) String language,
    ProjectSourceType sourceType) {
}
