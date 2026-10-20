package com.verireview.generation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Restructure request. The generation is only changed after a separate,
 * explicit apply call for the returned proposal.
 */
public record RestructureProposalRequest(
    @NotNull RestructurePreset preset,
    @Size(max = 2000) String description) {

  public enum RestructurePreset {
    MVC,
    LAYERED,
    FEATURE_FOLDERS,
    CUSTOM
  }
}
