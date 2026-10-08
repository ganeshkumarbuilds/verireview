package com.verireview.check.dto;

import java.time.Instant;
import java.util.UUID;

/** Check run gate response (VERIFY feature). */
public record CheckRunGateResponse(
    UUID id,
    UUID checkRunId,
    String gateName,
    String gateDescription,
    boolean passed,
    String evidence,
    String details) {
}