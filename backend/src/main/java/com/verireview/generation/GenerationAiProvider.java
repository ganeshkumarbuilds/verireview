package com.verireview.generation;

/**
 * AI providers the user may choose for generation. Credentials are supplied
 * per request, held in memory only for the run, and never persisted.
 *
 * <p>{@code NONE} means the project needs no AI key: the backend builds the
 * project from deterministic, dependency-free starter templates and verifies
 * it through the same sandbox build + deterministic verdict + review gates.
 * Templates currently cover Java Spring Boot-shaped starters only.
 */
public enum GenerationAiProvider {
  OPENROUTER,
  CUSTOM,
  NONE
}
