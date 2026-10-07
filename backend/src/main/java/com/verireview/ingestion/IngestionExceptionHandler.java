package com.verireview.ingestion;

import com.verireview.common.ApiError;
import com.verireview.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Handles ingestion-related exceptions with consistent JSON error responses.
 * Returns { "code": "...", "message": "<human-readable reason with actual limit and actual value>" }
 * with correct status codes: 413 for too large, 400 for invalid zip, 422 for too many files.
 * Ordered before GlobalExceptionHandler to ensure ingestion exceptions are handled specifically.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IngestionExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(IngestionExceptionHandler.class);

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    // The exception message already contains the max upload size
    String message = ex.getMessage() != null ? ex.getMessage() : "Upload exceeds the maximum allowed size of 100 MB.";
    log.warn("MaxUploadSizeExceededException on {}: {}", request.getRequestURI(), message);
    ApiError error = ApiError.of("payload_too_large", message);
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiResponse.error(error, com.verireview.common.TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ApiResponse<Void>> handleMultipartException(
      MultipartException ex, HttpServletRequest request) {
    Throwable cause = ex.getCause();
    String message = "Invalid multipart request: " + (cause != null ? cause.getMessage() : ex.getMessage());
    log.warn("MultipartException on {}: {}", request.getRequestURI(), message);
    ApiError error = ApiError.of("invalid_multipart", message);
    return ResponseEntity.badRequest()
        .body(ApiResponse.error(error, com.verireview.common.TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(ZipImportException.class)
  public ResponseEntity<ApiResponse<Void>> handleZipImportException(
      ZipImportException ex, HttpServletRequest request) {
    log.warn("ZipImportException on {}: {} (file={}, size={}, fileCount={})",
        request.getRequestURI(), ex.getMessage(), ex.getFileName(), ex.getFileSize(), ex.getFileCount());
    ApiError error = ApiError.of(ex.getCode(), ex.getMessage());
    return ResponseEntity.status(ex.getHttpStatus())
        .body(ApiResponse.error(error, com.verireview.common.TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(GitHubImportException.class)
  public ResponseEntity<ApiResponse<Void>> handleGitHubImportException(
      GitHubImportException ex, HttpServletRequest request) {
    log.warn("GitHubImportException on {}: {} (url={})",
        request.getRequestURI(), ex.getMessage(), ex.getUrl());
    ApiError error = ApiError.of(ex.getCode(), ex.getMessage());
    return ResponseEntity.status(ex.getHttpStatus())
        .body(ApiResponse.error(error, com.verireview.common.TraceIdFilter.currentTraceId()));
  }
}
