package com.verireview.review.dto;

import jakarta.validation.constraints.Size;

public record RetryReviewRequest(
    String stage,
    String provider,
    String model,
    @Size(max = 2000) String apiKey) {}
