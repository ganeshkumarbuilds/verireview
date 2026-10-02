package com.verireview.common;

import java.util.List;

/**
 * Standard envelope for all API responses (API_DESIGN §1).
 *
 * <p>Success: {@code data} present, {@code error} null. Failure: {@code error} present, {@code data}
 * null. Paginated lists use {@link PagedResponse} as {@code data}.
 */
public record ApiResponse<T>(
    T data,
    ApiError error,
    String traceId) {

  public static <T> ApiResponse<T> ok(T data, String traceId) {
    return new ApiResponse<>(data, null, traceId);
  }

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(data, null, TraceIdFilter.currentTraceId());
  }

  public static <T> ApiResponse<T> error(ApiError error, String traceId) {
    return new ApiResponse<>(null, error, traceId);
  }

  public static <T> ApiResponse<T> error(ApiError error) {
    return new ApiResponse<>(null, error, TraceIdFilter.currentTraceId());
  }

  public boolean isSuccess() {
    return error == null;
  }
}