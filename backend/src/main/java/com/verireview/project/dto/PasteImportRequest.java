package com.verireview.project.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PasteImportRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 5000) String description,
    @Size(max = 100) String language,
    @NotNull @Valid @Size(min = 1, max = 2000) List<PasteFileInput> files) {

  public record PasteFileInput(
      @NotBlank @Size(max = 500) String path,
      @NotBlank @Size(max = 1_000_000) String content) {
  }
}