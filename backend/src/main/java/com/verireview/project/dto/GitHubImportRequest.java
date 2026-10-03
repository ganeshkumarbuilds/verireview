package com.verireview.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GitHubImportRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 5000) String description,
    @Size(max = 100) String language,
    @NotBlank @Size(max = 500) String url) {
}