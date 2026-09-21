package com.verireview.generation.dto;

import com.verireview.generation.GenerationAiProvider;
import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationDatabase;
import com.verireview.generation.GenerationFrontend;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Generation wizard input. Secrets ({@code password}, {@code apiKey}) are
 * accepted here but never persisted, logged, or returned — the service holds
 * them in memory only for the run. Field-level Bean Validation mirrors the
 * frontend wizard: explicit stack choices, no hidden defaults.
 *
 * <p>Draft mode ({@code draft: true}) persists the request and configuration
 * only: secrets become optional and nothing is dispatched. Until secure
 * secret storage exists, drafts must not carry secrets-dependent promises —
 * supplied draft secrets are discarded, and starting a draft requires
 * re-supplying them.
 */
public record CreateGenerationRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 20000) String requirement,
    @Size(max = 5000) String description,
    @NotNull GenerationBackend backend,
    @NotNull GenerationFrontend frontend,
    @NotNull GenerationDatabase database,
    @Valid DatabaseConfig databaseConfig,
    @Valid @NotNull AiConfig aiConfig,
    Boolean draft) {

  public record DatabaseConfig(
      @NotBlank @Size(max = 500) String host,
      @NotNull @Min(1) @Max(65535) Integer port,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 200) String username,
      @Size(max = 500) String password,
      @Size(max = 50) String sslMode) {
  }

  public record AiConfig(
      @NotNull GenerationAiProvider provider,
      @Size(max = 2000) String apiKey,
      @Size(max = 500) String baseUrl,
      @Size(max = 200) String model) {
  }
}
