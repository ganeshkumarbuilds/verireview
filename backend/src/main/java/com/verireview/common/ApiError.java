package com.verireview.common;

import java.util.Map;

/**
 * Error detail carried inside {@link ApiResponse}.
 *
 * <p>Carries the HTTP {@code status} alongside the machine {@code code} and the
 * human {@code message}, so a failure body always answers "what status, what
 * code, what happened" without a separate lookup. The {@code message} is always
 * a plain-language sentence safe to show to a user -- never a stack trace.
 */
public record ApiError(
    Integer status,
    String code,
    String message,
    Map<String, Object> details) {

  public static ApiError of(String code, String message) {
    return new ApiError(null, code, message, null);
  }

  public static ApiError of(String code, String message, Map<String, Object> details) {
    return new ApiError(null, code, message, details);
  }

  public static ApiError of(int status, String code, String message) {
    return new ApiError(status, code, message, null);
  }

  public static ApiError of(int status, String code, String message, Map<String, Object> details) {
    return new ApiError(status, code, message, details);
  }
}
