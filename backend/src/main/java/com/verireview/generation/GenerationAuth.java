package com.verireview.generation;

/**
 * Explicit authentication choice for a generated project. No hidden defaults:
 * {@link #NONE} means the user asked for an unauthenticated starter.
 */
public enum GenerationAuth {
  JWT,
  OAUTH_GOOGLE,
  NONE
}
