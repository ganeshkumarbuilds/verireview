package com.verireview.generation;

/**
 * Per-run secrets. In-memory only: handed to the async runner, used for the
 * AI calls and the generated-content secret scan, then discarded. Never
 * persisted, never logged, never serialized into any response. The
 * {@link Generation} row has no columns for these values by design.
 */
public record GenerationSecrets(String dbPassword, String aiApiKey) {

  public GenerationSecrets {
    // Normalize blanks so callers can treat "absent" uniformly.
    dbPassword = dbPassword == null || dbPassword.isBlank() ? null : dbPassword;
    aiApiKey = aiApiKey == null || aiApiKey.isBlank() ? null : aiApiKey;
  }
}
