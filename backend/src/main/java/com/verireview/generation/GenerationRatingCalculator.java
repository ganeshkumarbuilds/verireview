package com.verireview.generation;

import com.verireview.generation.dto.GenerationResponse;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;
import java.util.List;

/** Deterministic rating from persisted review findings. */
public final class GenerationRatingCalculator {

  private GenerationRatingCalculator() {
  }

  public static GenerationResponse.RatingView rate(List<Finding> findings) {
    int syntaxPenalty = 0;
    int logicPenalty = 0;
    int securityPenalty = 0;
    int stylePenalty = 0;
    int totalPenalty = 0;

    for (Finding finding : findings) {
      int penalty = penalty(finding.getSeverity());
      totalPenalty += penalty;
      switch (category(finding)) {
        case "security" -> securityPenalty += penalty;
        case "style" -> stylePenalty += penalty;
        case "syntax" -> syntaxPenalty += penalty;
        default -> logicPenalty += penalty;
      }
    }

    return new GenerationResponse.RatingView(
        score(totalPenalty),
        score(syntaxPenalty),
        score(logicPenalty),
        score(securityPenalty),
        score(stylePenalty));
  }

  static String category(Finding finding) {
    String details = String.join(
        " ",
        safe(finding.getTitle()),
        safe(finding.getRule()),
        safe(finding.getAnalyzer())).toLowerCase(java.util.Locale.ROOT);
    if (details.contains("syntax") || details.contains("parse error")
        || details.contains("compilation error")) {
      return "syntax";
    }
    if (finding.getCategory() == FindingCategory.SECURITY) {
      return "security";
    }
    if (finding.getCategory() == FindingCategory.STYLE
        || finding.getCategory() == FindingCategory.CODE_QUALITY) {
      return "style";
    }
    return "logic";
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static int penalty(FindingSeverity severity) {
    if (severity == null) {
      return 0;
    }
    return switch (severity) {
      case CRITICAL -> 35;
      case HIGH -> 20;
      case MEDIUM -> 10;
      case LOW -> 4;
      case INFO -> 1;
    };
  }

  private static int score(int penalty) {
    return Math.max(0, 100 - penalty);
  }
}
