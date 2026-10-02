package com.verireview.common;

import java.util.Map;

/** Error detail carried inside {@link ApiResponse}. */
public record ApiError(
    String code,
    String message,
    Map<String, Object> details) {

  public static ApiError of(String code, String message) {
    return new ApiError(code, message, null);
  }

  public static ApiError of(String code, String message, Map<String, Object> details) {
    return new ApiError(code, message, details);
  }
}