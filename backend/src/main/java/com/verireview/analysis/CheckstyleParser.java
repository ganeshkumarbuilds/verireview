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
 * Checkstyle XML adapter ({@code <checkstyle><file name><error …/>}).
 * Sun-checks violations are style nits: category STYLE, error→MEDIUM,
 * warning→LOW, else INFO. File paths are relativized to the snapshot root.
 */
final class CheckstyleParser {

  static final String ANALYZER = "checkstyle";

  private CheckstyleParser() {
  }

  static List<NormalizedFinding> parse(Path report, Path srcRoot) {
    List<NormalizedFinding> findings = new ArrayList<>();
    Document document = ReportXml.parse(report);
    if (document == null) {
      return findings;
    }
    for (Element file : ReportXml.descendants(document, "file")) {
      String relative = relativize(file.getAttribute("name"), srcRoot);
      for (Element error : ReportXml.children(file, "error")) {
        String source = error.getAttribute("source");
        String rule = simpleRule(source);
        String message = error.getAttribute("message");
        Integer line = ReportXml.intOrNull(error.getAttribute("line"));
        Integer column = ReportXml.intOrNull(error.getAttribute("column"));
        findings.add(new NormalizedFinding(
            ANALYZER,
            rule,
            FindingCategory.STYLE,
            severityOf(error.getAttribute("severity")),
            ReportXml.trim(rule, 500),
            ReportXml.trim(message, 2000),
            relative,
            line,
            line,
            EvidenceJson.of(Map.of(
                "analyzer", ANALYZER,
                "rule", rule,
                "toolSeverity", error.getAttribute("severity"),
                "column", column == null ? "" : column.toString(),
                "toolVersion", ToolVersions.CHECKSTYLE))));
      }
    }
    return findings;
  }

  private static String simpleRule(String source) {
    if (source == null || source.isBlank()) {
      return "UnknownCheck";
    }
    String simple = source.substring(source.lastIndexOf('.') + 1);
    return simple.endsWith("Check") && simple.length() > "Check".length()
        ? simple.substring(0, simple.length() - "Check".length())
        : simple;
  }

  private static FindingSeverity severityOf(String severity) {
    return switch (severity == null ? "" : severity.toLowerCase()) {
      case "error" -> FindingSeverity.MEDIUM;
      case "warning" -> FindingSeverity.LOW;
      default -> FindingSeverity.INFO;
    };
  }

  static String relativize(String absolute, Path srcRoot) {
    if (absolute == null || absolute.isBlank()) {
      return null;
    }
    String unified = absolute.replace('\\', '/');
    String root = srcRoot.toAbsolutePath().toString().replace('\\', '/');
    if (unified.equals(root)) {
      return ".";
    }
    String prefix = root.endsWith("/") ? root : root + "/";
    if (unified.startsWith(prefix)) {
      return unified.substring(prefix.length());
    }
    if (unified.startsWith("/")) {
      // Absolute path from another root: keep the structure, drop the anchor.
      String stripped = unified.replaceAll("^/+", "");
      return stripped.isEmpty() ? null : stripped;
    }
    // Already relative (e.g. SpotBugs sourcepath): keep as reported.
    return unified;
  }
}
