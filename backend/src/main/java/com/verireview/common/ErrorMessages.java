package com.verireview.common;

/**
 * Helpers for turning raw exception/AI text into short, user-safe messages
 * (AGENTS.md rule 17/7: never surface stack traces or internal details).
 */
public final class ErrorMessages {

  private ErrorMessages() {
  }

  /**
   * Returns a short, plain-language line for a raw error. Only the first line is
   * kept, common exception class prefixes are stripped, and the result is capped.
   * Never returns a stack trace.
   */
  public static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String first = raw.strip().split("\\R", 2)[0].strip();
    first = first.replaceAll(
        "^(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error):\\s*", "");
    if (first.length() > 300) {
      first = first.substring(0, 300) + "…";
    }
    return first;
  }
}
