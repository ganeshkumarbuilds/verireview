package com.verireview.generation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A proposed or applied file-move plan. Paths are project-relative and never
 * include a server storage path.
 */
public record RestructureView(
    UUID id,
    UUID generationId,
    int iteration,
    String preset,
    String description,
    String status,
    List<Move> moves,
    List<String> directories,
    String notes,
    boolean canRevert,
    Instant createdAt) {

  public record Move(String from, String to) {
  }
}
