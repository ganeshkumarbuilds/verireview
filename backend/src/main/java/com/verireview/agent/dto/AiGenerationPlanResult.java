package com.verireview.agent.dto;

import java.util.List;

/**
 * AI → backend generation plan (mirrors Python {@code PlanResult}).
 * Structured sections describe the intended architecture; every section has
 * a default so older prompts and stored fixtures keep parsing.
 */
public record AiGenerationPlanResult(
    String generationId,
    List<PlannedFile> files,
    String notes,
    PlanSections sections) {

  public record PlannedFile(String path, String purpose) {
  }

  public record PlanSections(
      String architecture,
      List<String> dependencies,
      List<String> directories,
      String apis,
      List<String> steps) {

    public static PlanSections empty() {
      return new PlanSections("", List.of(), List.of(), "", List.of());
    }
  }
}
