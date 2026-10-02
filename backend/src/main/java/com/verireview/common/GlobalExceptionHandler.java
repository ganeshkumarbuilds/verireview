package com.verireview.common;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized exception handling mapping exceptions to the standard {@link ApiResponse} envelope.
 * Every response includes the request's trace ID from MDC.
 *
 * <p>Never leaks stack traces or internal details to clients. All errors are logged server-side at
 * WARN/ERROR level with full context.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code = status.name().replace("_", " ").toLowerCase();
    ApiError error = ApiError.of(code, ex.getReason() != null ? ex.getReason() : status.getReasonPhrase());
    return ResponseEntity.status(status).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, Object> details = ex.getBindingResult().getFieldErrors().stream()
        .collect(Collectors.toMap(
            FieldError::getField,
            e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "invalid",
            (a, b) -> a,
            LinkedHashMap::new));
    ApiError error = ApiError.of("validation_failed", "Request validation failed", details);
    return ResponseEntity.badRequest().body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    Map<String, Object> details = ex.getConstraintViolations().stream()
        .collect(Collectors.toMap(
            v -> v.getPropertyPath().toString(),
            v -> v.getMessage(),
            (a, b) -> a,
            LinkedHashMap::new));
    ApiError error = ApiError.of("validation_failed", "Constraint validation failed", details);
    return ResponseEntity.badRequest().body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleMalformedJson(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    ApiError error = ApiError.of("malformed_request", "Request body is not valid JSON");
    return ResponseEntity.badRequest().body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    ApiError error = ApiError.of(
        "type_mismatch",
        "Parameter '" + ex.getName() + "' has invalid type: expected " + ex.getRequiredType().getSimpleName());
    return ResponseEntity.badRequest().body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ApiResponse<Void>> handleBadCredentials(
      BadCredentialsException ex, HttpServletRequest request) {
    ApiError error = ApiError.of("unauthorized", "Invalid credentials");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    ApiError error = ApiError.of("forbidden", "Insufficient permissions");
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(EntityNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(
      EntityNotFoundException ex, HttpServletRequest request) {
    ApiError error = ApiError.of("not_found", "Resource not found");
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    String message = ex.getMostSpecificCause() != null
        ? ex.getMostSpecificCause().getMessage()
        : ex.getMessage();
    String detail = (message != null && message.contains("duplicate")) ? "Duplicate value" : "Data integrity violation";
    ApiError error = ApiError.of("conflict", detail);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNoResource(
      NoResourceFoundException ex, HttpServletRequest request) {
    ApiError error = ApiError.of("not_found", "Endpoint not found");
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleGeneric(
      Exception ex, HttpServletRequest request) {
    // Log full stack trace server-side; never expose to client
    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
        .error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
    ApiError error = ApiError.of("internal_error", "An unexpected error occurred");
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(error, TraceIdFilter.currentTraceId()));
  }
}