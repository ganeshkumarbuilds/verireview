package com.verireview.check.dto;

/** Summary of open issues by severity. */
public record SeveritySummary(
    String severity,
    int count) {
}