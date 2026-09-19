package com.verireview.analysis;

import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PMD XML adapter ({@code <pmd><file name><violation rule ruleset
 * beginline endline priority>}). Category follows the ruleset family;
 * priority 1–2→HIGH, 3→MEDIUM, 4→LOW, 5→INFO.
 */
final class PmdParser {

  static final String ANALYZER = "pmd";

  private PmdParser() {
  }

  static List<NormalizedFinding> parse(Path report, Path srcRoot) {
    List<NormalizedFinding> findings = new ArrayList<>();
    Document document = ReportXml.parse(report);
    if (document == null) {
      return findings;
    }
    for (Element file : ReportXml.descendants(document, "file")) {
      String relative = CheckstyleParser.relativize(file.getAttribute("name"), srcRoot);
      for (Element violation : ReportXml.children(file, "violation")) {
        String rule = violation.getAttribute("rule");
        if (rule.isBlank()) {
          rule = "UnknownRule";
        }
        String ruleset = violation.getAttribute("ruleset");
        String message = violation.getTextContent();
        Integer begin = ReportXml.intOrNull(violation.getAttribute("beginline"));
        Integer end = ReportXml.intOrNull(violation.getAttribute("endline"));
        findings.add(new NormalizedFinding(
            ANALYZER,
            rule,
            categoryOf(ruleset),
            severityOf(ReportXml.intOrNull(violation.getAttribute("priority"))),
            ReportXml.trim(rule, 500),
            ReportXml.trim(message, 2000),
            relative,
            begin,
            end == null ? begin : end,
            EvidenceJson.of(Map.of(
                "analyzer", ANALYZER,
                "rule", rule,
                "ruleset", ruleset,
                "priority", violation.getAttribute("priority"),
                "toolVersion", ToolVersions.PMD))));
      }
    }
    return findings;
  }

  private static FindingCategory categoryOf(String ruleset) {
    if (ruleset == null) {
      return FindingCategory.CODE_QUALITY;
    }
    String family = ruleset.trim().toLowerCase();
    return switch (family) {
      case "security" -> FindingCategory.SECURITY;
      case "performance" -> FindingCategory.PERFORMANCE;
      case "error prone", "multithreading" -> FindingCategory.BUG;
      default -> FindingCategory.CODE_QUALITY;
    };
  }

  private static FindingSeverity severityOf(Integer priority) {
    if (priority == null) {
      return FindingSeverity.MEDIUM;
    }
    if (priority <= 2) {
      return FindingSeverity.HIGH;
    }
    if (priority == 3) {
      return FindingSeverity.MEDIUM;
    }
    if (priority == 4) {
      return FindingSeverity.LOW;
    }
    return FindingSeverity.INFO;
  }
}
