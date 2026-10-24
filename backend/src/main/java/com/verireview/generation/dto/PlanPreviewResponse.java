package com.verireview.generation.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Stack-aware scaffold shown at step 4 before any generation job exists.
 *
 * <p>This is deliberately labelled a scaffold, not a claim that an agent ran:
 * the real Planner Agent output replaces it once generation starts.
 */
public record PlanPreviewResponse(
    @NotBlank @Size(max = 2000)
    String architecture,
    @NotNull @Size(max = 100) List<@NotBlank @Size(max = 200) String> dependencies,
    @NotNull @Size(max = 100) List<@NotBlank @Size(max = 500) String> directories,
    @NotBlank @Size(max = 10000)
    String apis,
    @NotNull @Size(max = 100) List<@NotBlank @Size(max = 1000) String> steps,
    @NotNull @Size(max = 60) List<@NotNull @Valid PlanFile> files,
    @NotBlank @Size(max = 5000)
    String notes) {

  public record PlanFile(
      @NotBlank @Size(max = 500) String path,
      @NotBlank @Size(max = 1000) String purpose) {
  }
}
