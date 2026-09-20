package com.verireview.agent.dto;

import java.util.List;

/** AI → backend generated files (mirrors Python {@code FilesResult}). */
public record AiGenerationFilesResult(
    String generationId,
    List<GeneratedFile> files,
    String notes) {

  public record GeneratedFile(String path, String content, String language) {
  }
}
