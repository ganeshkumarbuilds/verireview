package com.verireview.review.dto;

import jakarta.validation.constraints.Size;

/**
 * Per-run AI credentials. The API key is held only in memory by the async
 * worker and is never persisted, returned, or logged.
 */
public record StartReviewRequest(
    @Size(max = 50) String provider,
    @Size(max = 200) String model,
    @Size(max = 2000) String apiKey) {
}
