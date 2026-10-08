package com.verireview.check.dto;

import java.time.Instant;

/** Trend point for health score over time. */
public record TrendPoint(
    Instant timestamp,
    int generateScore,
    int reviewScore,
    int fixScore,
    int verifyScore,
    int totalScore) {
}