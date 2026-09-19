package com.verireview.ingestion;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Oversized multipart bodies never reach the controllers: the servlet
 * container rejects them first, and this advice turns that into a 413 with a
 * plain message (no stack trace, no internals).
 */
@RestControllerAdvice
public class IngestionExceptionHandler {

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> oversized(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("message", "Upload exceeds the 50 MB project limit"));
  }
}
