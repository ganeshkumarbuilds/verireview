package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Phase A: the generation state machine. Pure unit test (no Spring) — every
 * pipeline edge from the spec is legal, terminal states have no exits, and
 * failure/cancel remain reachable until termination.
 */
class GenerationStatusTest {

  @Test
  void draftAndReadyEnterThePipeline() {
    assertThat(GenerationStatus.DRAFT.canTransitionTo(GenerationStatus.READY)).isTrue();
    assertThat(GenerationStatus.DRAFT.canTransitionTo(GenerationStatus.CANCELLED)).isTrue();
    assertThat(GenerationStatus.DRAFT.canTransitionTo(GenerationStatus.PLANNING)).isFalse();
    assertThat(GenerationStatus.READY.canTransitionTo(GenerationStatus.QUEUED)).isTrue();
    assertThat(GenerationStatus.READY.canTransitionTo(GenerationStatus.PLANNING)).isTrue();
    assertThat(GenerationStatus.READY.canTransitionTo(GenerationStatus.DRAFT)).isFalse();
  }

  @Test
  void agentPipelineEdges() {
    assertThat(GenerationStatus.QUEUED.canTransitionTo(GenerationStatus.PLANNING)).isTrue();
    assertThat(GenerationStatus.PLANNING.canTransitionTo(GenerationStatus.CODING)).isTrue();
    assertThat(GenerationStatus.PLANNING.canTransitionTo(GenerationStatus.GENERATING)).isTrue();
    assertThat(GenerationStatus.CODING.canTransitionTo(GenerationStatus.BUILDING)).isTrue();
    assertThat(GenerationStatus.CODING.canTransitionTo(GenerationStatus.VERIFYING)).isFalse();
    assertThat(GenerationStatus.BUILDING.canTransitionTo(GenerationStatus.TESTING)).isTrue();
    assertThat(GenerationStatus.BUILDING.canTransitionTo(GenerationStatus.VERIFYING)).isTrue();
    assertThat(GenerationStatus.TESTING.canTransitionTo(GenerationStatus.VERIFYING)).isTrue();
    assertThat(GenerationStatus.VERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isTrue();
    assertThat(GenerationStatus.REVIEWING.canTransitionTo(GenerationStatus.FIXING)).isTrue();
    assertThat(GenerationStatus.REVIEWING.canTransitionTo(GenerationStatus.COMPLETED)).isTrue();
    assertThat(GenerationStatus.FIXING.canTransitionTo(GenerationStatus.REBUILDING)).isTrue();
    assertThat(GenerationStatus.REBUILDING.canTransitionTo(GenerationStatus.REVERIFYING)).isTrue();
    assertThat(GenerationStatus.REBUILDING.canTransitionTo(GenerationStatus.TESTING)).isTrue();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isTrue();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.COMPLETED)).isFalse();
  }

  @Test
  void failureAndCancelAlwaysReachableUntilTermination() {
    for (GenerationStatus status : GenerationStatus.values()) {
      if (status.isTerminal()) {
        assertThat(status.allowedTransitions()).isEmpty();
      } else if (status == GenerationStatus.DRAFT) {
        // A draft was never submitted for execution, so there is nothing to
        // fail — but the user can always abandon it. Submitted states
        // (READY/QUEUED) can fail with "could not run".
        assertThat(status.canTransitionTo(GenerationStatus.FAILED)).isFalse();
        assertThat(status.canTransitionTo(GenerationStatus.CANCELLED)).isTrue();
      } else {
        assertThat(status.canTransitionTo(GenerationStatus.FAILED))
            .as("FAILED from %s", status).isTrue();
        assertThat(status.canTransitionTo(GenerationStatus.CANCELLED))
            .as("CANCELLED from %s", status).isTrue();
      }
    }
  }

  @Test
  void terminalStates() {
    assertThat(GenerationStatus.COMPLETED.isTerminal()).isTrue();
    assertThat(GenerationStatus.FAILED.isTerminal()).isTrue();
    assertThat(GenerationStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(GenerationStatus.DRAFT.isTerminal()).isFalse();
    assertThat(GenerationStatus.REVIEWING.isTerminal()).isFalse();
  }

  @Test
  void nullTargetNeverAllowed() {
    assertThat(GenerationStatus.DRAFT.canTransitionTo(null)).isFalse();
  }
}
