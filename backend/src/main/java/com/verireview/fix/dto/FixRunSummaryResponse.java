package com.verireview.fix.dto;

/**
 * Before/after view of a fix run's re-check.
 *
 * @param fixed          findings that are now verified fixed
 * @param stillOpen      findings that remain open
 * @param newlyIntroduced findings discovered by the re-check that did not exist before
 */
public record FixRunSummaryResponse(
    int fixed,
    int stillOpen,
    int newlyIntroduced) {}
