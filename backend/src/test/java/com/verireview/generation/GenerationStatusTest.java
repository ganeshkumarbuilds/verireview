package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Current generation state-machine contract.
 *
 * The finalized pipeline is:
 * DRAFT -> READY -> QUEUED -> PLANNING -> CODING -> BUILDING
 * -> VERIFYING -> VERIFIED -> REVIEWING -> REVIEWED
 *
 * REVIEWED is intentionally not terminal because the approved generation
 * fix/rebuild workflow starts from REVIEWED.
 */
class GenerationStatusTest {

  @Test
  void initialPipelineEdgesAreLegal() {
    assertThat(GenerationStatus.DRAFT.canTransitionTo(GenerationStatus.READY)).isTrue();
    assertThat(GenerationStatus.READY.canTransitionTo(GenerationStatus.QUEUED)).isTrue();
    assertThat(GenerationStatus.QUEUED.canTransitionTo(GenerationStatus.PLANNING)).isTrue();
    assertThat(GenerationStatus.PLANNING.canTransitionTo(GenerationStatus.CODING)).isTrue();
    assertThat(GenerationStatus.CODING.canTransitionTo(GenerationStatus.BUILDING)).isTrue();
  }

  @Test
  void verificationAndReviewEdgesAreLegal() {
    assertThat(GenerationStatus.BUILDING.canTransitionTo(GenerationStatus.VERIFYING)).isTrue();
    assertThat(GenerationStatus.VERIFYING.canTransitionTo(GenerationStatus.VERIFIED)).isTrue();
    assertThat(GenerationStatus.VERIFIED.canTransitionTo(GenerationStatus.REVIEWING)).isTrue();
    assertThat(GenerationStatus.REVIEWING.canTransitionTo(GenerationStatus.REVIEWED)).isTrue();

    assertThat(GenerationStatus.CODING.canTransitionTo(GenerationStatus.VERIFYING)).isFalse();
    assertThat(GenerationStatus.VERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isFalse();
  }

  @Test
  void rebuildEdgesAreLegal() {
    assertThat(GenerationStatus.REVIEWED.canTransitionTo(GenerationStatus.REBUILDING)).isTrue();
    assertThat(GenerationStatus.REBUILDING.canTransitionTo(GenerationStatus.REVERIFYING)).isTrue();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.VERIFIED)).isTrue();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.FAILED)).isTrue();

    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isFalse();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.COMPLETED)).isFalse();
  }

  @Test
  void failureAndCancellationRemainAvailableForActiveStates() {
    for (GenerationStatus status : GenerationStatus.values()) {
      if (status.isTerminal()) {
        assertThat(status.allowedTransitions()).isEmpty();
      } else if (status == GenerationStatus.DRAFT) {
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
  void terminalStatesHaveNoExitsAndNullIsRejected() {
    assertThat(GenerationStatus.FAILED.isTerminal()).isTrue();
    assertThat(GenerationStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(GenerationStatus.VERIFIED.isTerminal()).isFalse();
    assertThat(GenerationStatus.REVIEWED.isTerminal()).isFalse();
    assertThat(GenerationStatus.DRAFT.canTransitionTo(null)).isFalse();
  }
}
