package com.verireview.agent;

/**
 * AI-service call failure. Every kind degrades the same way: the review keeps
 * its deterministic findings and records a skip note — the AI step never
 * fails the whole analysis (deterministic-first, AGENT_DESIGN §1).
 */
public class AiServiceException extends RuntimeException {

  /** Failure classes, from most to least transient. */
  public enum Kind {
    TIMEOUT,
    UNAVAILABLE,
    BAD_STATUS,
    MALFORMED
  }

  private final Kind kind;

  public AiServiceException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public AiServiceException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
