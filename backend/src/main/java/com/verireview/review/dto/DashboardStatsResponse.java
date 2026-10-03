package com.verireview.review.dto;

import java.util.UUID;

public record DashboardStatsResponse(
    UUID projectId,
    String projectName,
    long totalFindings,
    long openFindings,
    long fixedFindings,
    long verifiedFindings,
    long rejectedFindings,
    long wontfixFindings,
    long criticalHighFindings,
    long deterministicFindings,
    long aiFindings,
    int latestReviewId,
    String latestReviewStatus,
    long totalReviews
) {
}