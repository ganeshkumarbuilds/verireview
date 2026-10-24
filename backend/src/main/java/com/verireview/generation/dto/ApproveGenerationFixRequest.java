package com.verireview.generation.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ApproveGenerationFixRequest(
    @Size(max = 200) List<UUID> findingIds,
    @Size(max = 2000) String apiKey) {
}
