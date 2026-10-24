package com.verireview.generation.dto;

import jakarta.validation.constraints.Size;

public record RetryGenerationStageRequest(@Size(max = 2000) String apiKey) {
}
