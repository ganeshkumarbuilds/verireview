package com.verireview.generation;

/**
 * AI providers the user may choose for generation. Credentials are supplied
 * per request, held in memory only for the run, and never persisted.
 */
public enum GenerationAiProvider {
  OPENROUTER,
  CUSTOM
}
