package com.verireview.review.dto;

import com.verireview.generation.dto.GenerationResponse;
import com.verireview.review.ReviewStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

/** Review execution view with analysis status tracking. */
public record ReviewResponse(
    UUID id,
    UUID projectId,
    ReviewStatus status,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    int findingCount,
    int fixRound,
    String error,
    int filesAnalyzed,
    int filesTotal,
    List<AnalysisCheckResponse> checks,
    List<AnalysisFileResponse> files,
    GenerationResponse.RatingView rating,
    List<ActivityResponse> activity,
    Instant createdAt) {

  public record AnalysisCheckResponse(
      String analyzer,
      String status,
      String reason,
      Long durationMs,
      int filesAnalyzed,
      int findingCount) {
  }

  public record AnalysisFileResponse(
      String path,
      String status,
      String reason,
      long sizeBytes,
      int findingCount) {
  }

  public record ActivityResponse(String message, Instant at) {
  }
}
