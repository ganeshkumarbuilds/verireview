package com.verireview.generation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Generation lifecycle. Driven by real backend state only — the async runner
 * moves the row forward, the frontend polls and renders exactly this.
 *
 * <p>Foundation states {@code DRAFT} (saved configuration, nothing
 * dispatched) and {@code READY} (submitted, awaiting dispatch) are the only
 * states the workspace phase uses. Agent states ({@code PLANNING} …) are
 * driven by later pipeline phases, never faked: every edge below is enforced
 * by {@link GenerationService} before any status write, and the terminal
 * states ({@code COMPLETED}, {@code FAILED}, {@code CANCELLED}) have no
 * outgoing edges.
 */
public enum GenerationStatus {
  DRAFT,
  READY,
  QUEUED,
  PLANNING,
  GENERATING,
  CODING,
  BUILDING,
  REVIEWING,
  FIXING,
  REBUILDING,
  TESTING,
  REVERIFYING,
  VERIFYING,
  VERIFIED,
  REVIEWED,
  COMPLETED,
  FAILED,
  CANCELLED;

  /**
   * Legal outgoing edges. {@code FAILED} and {@code CANCELLED} are reachable
   * from every non-terminal state so a run can always stop honestly; nothing
   * leaves a terminal state.
   */
  public Set<GenerationStatus> allowedTransitions() {
    return switch (this) {
      case DRAFT -> EnumSet.of(READY, CANCELLED);
      case READY -> EnumSet.of(QUEUED, PLANNING, FAILED, CANCELLED);
      case QUEUED -> EnumSet.of(PLANNING, FAILED, CANCELLED);
      case PLANNING -> EnumSet.of(CODING, GENERATING, FAILED, CANCELLED);
      case GENERATING -> EnumSet.of(REVIEWING, FAILED, CANCELLED);
      case CODING -> EnumSet.of(BUILDING, FAILED, CANCELLED);
      case BUILDING -> EnumSet.of(TESTING, VERIFYING, FAILED, CANCELLED);
      case TESTING -> EnumSet.of(VERIFYING, FAILED, CANCELLED);
      case VERIFYING -> EnumSet.of(VERIFIED, FAILED, CANCELLED);
      case REVIEWING -> EnumSet.of(FIXING, REVIEWED, COMPLETED, FAILED, CANCELLED);
      case FIXING -> EnumSet.of(REBUILDING, FAILED, CANCELLED);
      case REBUILDING -> EnumSet.of(TESTING, REVERIFYING, FAILED, CANCELLED);
      case REVERIFYING -> EnumSet.of(REVIEWING, FAILED, CANCELLED);
      case VERIFIED -> EnumSet.of(REVIEWING, FAILED, CANCELLED);
      case REVIEWED, COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(GenerationStatus.class);
    };
  }

  public boolean canTransitionTo(GenerationStatus next) {
    return next != null && allowedTransitions().contains(next);
  }

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
