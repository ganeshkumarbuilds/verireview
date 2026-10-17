package com.verireview.check.dto;

import java.util.List;

/**
 * Dashboard summary response: owner-scoped totals plus a per-project rollup.
 * DTOs only — no JPA entities are ever serialized (AGENTS.md rule 6).
 */
public record DashboardSummaryResponse(
    long totalFilesVerified,
    long totalIssuesFound,
    long totalIssuesFixed,
    List<ProjectSummary> projects) {
}
