package com.verireview.analysis;

import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;

/**
 * One tool hit in backend-neutral shape (roadmap "normalized
 * DeterministicFinding"). Adapters translate raw tool output into these;
 * the job service persists them as {@code findings} rows.
 */
public record NormalizedFinding(
    String analyzer,
    String rule,
    FindingCategory category,
    FindingSeverity severity,
    String title,
    String description,
    String filePath,
    Integer lineStart,
    Integer lineEnd,
    String evidenceJson) {
}
