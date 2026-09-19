package com.verireview.project.dto;

import jakarta.validation.constraints.Size;

/** Rename/describe. Null fields mean "no change". */
public record UpdateProjectRequest(
    @Size(min = 1, max = 200) String name,
    @Size(max = 5000) String description) {
}
