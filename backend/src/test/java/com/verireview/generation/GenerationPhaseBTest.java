package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Focused regression coverage for the finalized generation pipeline and
 * generation-owned verification/rebuild contract.
 *
 * Integration coverage of the asynchronous worker lives in GenerationTest.
 * This class deliberately keeps the state/persistence contract independent
 * from Docker and external AI services.
 */
class GenerationPhaseBTest {

  @Test
  void finalizedPipelineUsesVerifiedBeforeReview() {
    assertThat(GenerationStatus.CODING.canTransitionTo(GenerationStatus.BUILDING)).isTrue();
    assertThat(GenerationStatus.BUILDING.canTransitionTo(GenerationStatus.VERIFYING)).isTrue();
    assertThat(GenerationStatus.VERIFYING.canTransitionTo(GenerationStatus.VERIFIED)).isTrue();
    assertThat(GenerationStatus.VERIFIED.canTransitionTo(GenerationStatus.REVIEWING)).isTrue();
    assertThat(GenerationStatus.REVIEWING.canTransitionTo(GenerationStatus.REVIEWED)).isTrue();

    assertThat(GenerationStatus.VERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isFalse();
    assertThat(GenerationStatus.VERIFIED.canTransitionTo(GenerationStatus.REVIEWED)).isFalse();
  }

  @Test
  void generationReviewIsTheSuccessfulGenerationTerminalWorkflowState() {
    assertThat(GenerationStatus.REVIEWED.isTerminal()).isFalse();
    assertThat(GenerationStatus.REVIEWED.canTransitionTo(GenerationStatus.REBUILDING)).isTrue();
    assertThat(GenerationStatus.REBUILDING.canTransitionTo(GenerationStatus.REVERIFYING)).isTrue();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.VERIFIED)).isTrue();
  }

  @Test
  void failedVerificationCannotSkipToReview() {
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isFalse();
    assertThat(GenerationStatus.VERIFYING.canTransitionTo(GenerationStatus.REVIEWING)).isFalse();
    assertThat(GenerationStatus.BUILDING.canTransitionTo(GenerationStatus.REVIEWING)).isFalse();
  }

  @Test
  void rebuildCannotBypassReverification() {
    assertThat(GenerationStatus.REVIEWED.canTransitionTo(GenerationStatus.REBUILDING)).isTrue();
    assertThat(GenerationStatus.REBUILDING.canTransitionTo(GenerationStatus.REVERIFYING)).isTrue();
    assertThat(GenerationStatus.REBUILDING.canTransitionTo(GenerationStatus.REVIEWED)).isFalse();
    assertThat(GenerationStatus.REVERIFYING.canTransitionTo(GenerationStatus.REVIEWED)).isFalse();
  }

  @Test
  void activePipelineStatesCanFail() {
    GenerationStatus[] active = {
        GenerationStatus.QUEUED,
        GenerationStatus.PLANNING,
        GenerationStatus.CODING,
        GenerationStatus.BUILDING,
        GenerationStatus.VERIFYING,
        GenerationStatus.REVIEWING,
        GenerationStatus.REBUILDING,
        GenerationStatus.REVERIFYING
    };

    for (GenerationStatus status : active) {
      assertThat(status.canTransitionTo(GenerationStatus.FAILED))
          .as("FAILED from %s", status)
          .isTrue();
    }
  }
}
