package com.verireview.check.dto;

import com.verireview.check.ProjectHealthScore;
import java.util.List;

/** Dashboard summary response. */
public record DashboardSummaryResponse(
    List<FeatureSummary> features,
    List<SeveritySummary> openIssuesBySeverity,
    List<RunSummary> lastRuns,
    List<TrendPoint> trend,
    ProjectHealthScore latestHealthScore) {
}