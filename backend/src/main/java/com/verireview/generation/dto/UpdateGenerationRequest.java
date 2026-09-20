package com.verireview.generation.dto;

import com.verireview.generation.GenerationAiProvider;
import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationDatabase;
import com.verireview.generation.GenerationFrontend;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Editable generation fields. Drafts only — editing a dispatched job is
 * rejected. Contains no secret fields at all: the database password and AI
 * key can never be set, changed, or read through this contract.
 */
public record UpdateGenerationRequest(
    @Size(max = 200) String name,
    @Size(max = 20000) String requirement,
    @Size(max = 5000) String description,
    GenerationBackend backend,
    GenerationFrontend frontend,
    GenerationDatabase database,
    @Valid DatabaseConfig databaseConfig,
    @Valid AiConfig aiConfig) {

  public record DatabaseConfig(
      @Size(max = 500) String host,
      @Min(1) @Max(65535) Integer port,
      @Size(max = 200) String name,
      @Size(max = 200) String username,
      @Size(max = 50) String sslMode) {
  }

  public record AiConfig(
      GenerationAiProvider provider,
      @Size(max = 500) String baseUrl,
      @Size(max = 200) String model) {
  }
}
