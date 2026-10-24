package com.verireview.generation.dto;

import java.util.List;

public record GenerationFixPreviewResponse(List<String> files, int findingCount) {
}
