package com.verireview.review.dto;

import java.util.List;

public record ReviewFixPreviewResponse(List<String> files, int findingCount) {
}
