package com.verireview.check.dto;

import java.time.Instant;
import java.util.UUID;

/** Per-project dashboard summary. */
public record ProjectSummary(
    UUID id,
    String name,
    long filesVerified,
    long issuesFound,
    long issuesFixed,
    Instant lastActivityAt) {
}