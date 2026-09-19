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
 * SpotBugs XML adapter ({@code <BugCollection><BugInstance type priority rank
 * category>}). Rank 1–4→HIGH, 5–9→MEDIUM, 10–14→LOW, else INFO (priority
 * fallback when rank is absent). File/line come from the primary
 * {@code SourceLine}; the class name backs the title context.
 */
final class SpotbugsParser {

  static final String ANALYZER = "spotbugs";

  private SpotbugsParser() {
  }

  static List<NormalizedFinding> parse(Path report, Path srcRoot) {
    List<NormalizedFinding> findings = new ArrayList<>();
    Document document = ReportXml.parse(report);
    if (document == null) {
      return findings;
    }
    for (Element bug : ReportXml.descendants(document, "BugInstance")) {
      String type = bug.getAttribute("type");
      if (type.isBlank()) {
        continue;
      }
      Element primary = primarySourceLine(bug);
      String relative = primary == null
          ? null
          : CheckstyleParser.relativize(primary.getAttribute("sourcepath"), srcRoot);
      Integer start = primary == null
          ? null : ReportXml.intOrNull(primary.getAttribute("start"));
      Integer end = primary == null
          ? null : ReportXml.intOrNull(primary.getAttribute("end"));
      String className = simpleClass(bug);
      String message = longMessage(bug);
      findings.add(new NormalizedFinding(
          ANALYZER,
          type,
          categoryOf(bug.getAttribute("category")),
          severityOf(ReportXml.intOrNull(bug.getAttribute("rank")),
              ReportXml.intOrNull(bug.getAttribute("priority"))),
          ReportXml.trim(type + (className == null ? "" : " in " + className), 500),
          ReportXml.trim(message, 2000),
          relative,
          start,
          end == null ? start : end,
          EvidenceJson.of(Map.of(
              "analyzer", ANALYZER,
              "rule", type,
              "bugCategory", bug.getAttribute("category"),
              "rank", bug.getAttribute("rank"),
              "toolVersion", ToolVersions.SPOTBUGS))));
    }
    return findings;
  }

  private static Element primarySourceLine(Element bug) {
    List<Element> lines = ReportXml.children(bug, "SourceLine");
    for (Element line : lines) {
      if ("true".equalsIgnoreCase(line.getAttribute("primary"))) {
        return line;
      }
    }
    return lines.isEmpty() ? null : lines.get(0);
  }

  private static String simpleClass(Element bug) {
    List<Element> classes = ReportXml.children(bug, "Class");
    if (classes.isEmpty()) {
      return null;
    }
    String name = classes.get(0).getAttribute("classname");
    if (name.isBlank()) {
      return null;
    }
    int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
    return cut >= 0 ? name.substring(cut + 1) : name;
  }

  private static String longMessage(Element bug) {
    List<Element> messages = ReportXml.children(bug, "LongMessage");
    return messages.isEmpty() ? null : messages.get(0).getTextContent();
  }

  private static FindingCategory categoryOf(String category) {
    if (category == null) {
      return FindingCategory.BUG;
    }
    return switch (category.trim().toUpperCase()) {
      case "SECURITY" -> FindingCategory.SECURITY;
      case "PERFORMANCE" -> FindingCategory.PERFORMANCE;
      case "STYLE" -> FindingCategory.STYLE;
      case "CORRECTNESS", "MT_CORRECTNESS", "BAD_PRACTICE", "EXPERIMENTAL", "I18N"
          -> FindingCategory.BUG;
      default -> FindingCategory.CODE_QUALITY;
    };
  }

  private static FindingSeverity severityOf(Integer rank, Integer priority) {
    if (rank != null) {
      if (rank <= 4) {
        return FindingSeverity.HIGH;
      }
      if (rank <= 9) {
        return FindingSeverity.MEDIUM;
      }
      if (rank <= 14) {
        return FindingSeverity.LOW;
      }
      return FindingSeverity.INFO;
    }
    if (priority == null) {
      return FindingSeverity.MEDIUM;
    }
    if (priority <= 2) {
      return FindingSeverity.HIGH;
    }
    if (priority == 3) {
      return FindingSeverity.MEDIUM;
    }
    return FindingSeverity.LOW;
  }
}
