package com.verireview.generation.dto;

import java.util.List;

/**
 * Stack-aware scaffold shown at step 4 before any generation job exists.
 *
 * <p>This is deliberately labelled a scaffold, not a claim that an agent ran:
 * the real Planner Agent output replaces it once generation starts.
 */
public record PlanPreviewResponse(
    String architecture,
    List<String> dependencies,
    List<String> directories,
    String apis,
    List<String> steps,
    List<PlanFile> files,
    String notes) {

  public record PlanFile(String path, String purpose) {
  }
}
