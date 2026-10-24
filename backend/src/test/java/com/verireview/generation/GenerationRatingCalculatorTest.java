package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationRatingCalculatorTest {

  @Test
  void ratesPersistedFindingsAndBreaksScoresDownByCategory() {
    Finding security = new Finding(
        null, FindingCategory.SECURITY, FindingSeverity.HIGH, FindingSource.AI, "Unsafe access");
    Finding style = new Finding(
        null, FindingCategory.CODE_QUALITY, FindingSeverity.LOW, FindingSource.DETERMINISTIC,
        "Unclear name");

    var rating = GenerationRatingCalculator.rate(List.of(security, style));

    assertThat(rating.overall()).isEqualTo(76);
    assertThat(rating.security()).isEqualTo(80);
    assertThat(rating.style()).isEqualTo(96);
    assertThat(rating.logic()).isEqualTo(100);
    assertThat(rating.syntax()).isEqualTo(100);
  }

  @Test
  void cleanReviewReceivesFullScore() {
    var rating = GenerationRatingCalculator.rate(List.of());

    assertThat(rating.overall()).isEqualTo(100);
    assertThat(rating.syntax()).isEqualTo(100);
    assertThat(rating.logic()).isEqualTo(100);
    assertThat(rating.security()).isEqualTo(100);
    assertThat(rating.style()).isEqualTo(100);
  }
}
