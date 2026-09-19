package com.verireview.analysis;

import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OWASP Dependency-Check JSON adapter
 * ({@code dependencies[] → vulnerabilities[]}). Runs only where practical
 * (the runner disables it by default — it needs an NVD mirror); the parser
 * itself is fully implemented and covered by golden fixtures.
 */
final class DependencyCheckParser {

  static final String ANALYZER = "dependency-check";

  private static final Logger log = LoggerFactory.getLogger(DependencyCheckParser.class);
  private static final ObjectMapper OBJECTS = new ObjectMapper();

  private DependencyCheckParser() {
  }

  static List<NormalizedFinding> parse(Path report, Path srcRoot) {
    List<NormalizedFinding> findings = new ArrayList<>();
    if (report == null || !Files.isRegularFile(report)) {
      return findings;
    }
    final JsonNode root;
    try {
      root = OBJECTS.readTree(Files.readString(report));
    } catch (Exception e) {
      log.warn("Ignoring malformed dependency-check report {}: {}", report, e.toString());
      return findings;
    }
    JsonNode dependencies = root.path("dependencies");
    if (!dependencies.isArray()) {
      return findings;
    }
    for (JsonNode dependency : dependencies) {
      String fileName = dependency.path("fileName").asString(null);
      String filePath = relativize(dependency.path("filePath").asString(fileName), srcRoot);
      JsonNode vulnerabilities = dependency.path("vulnerabilities");
      if (!vulnerabilities.isArray()) {
        continue;
      }
      for (JsonNode vulnerability : vulnerabilities) {
        String name = vulnerability.path("name").asString(null);
        if (name == null || name.isBlank()) {
          continue;
        }
        String severity = vulnerability.path("severity").asString("");
        String description = vulnerability.path("description").asString(null);
        findings.add(new NormalizedFinding(
            ANALYZER,
            name,
            FindingCategory.DEPENDENCY,
            severityOf(severity),
            ReportXml.trim("Dependency " + fileName + ": " + name, 500),
            ReportXml.trim(description, 2000),
            filePath,
            null,
            null,
            EvidenceJson.of(Map.of(
                "analyzer", ANALYZER,
                "rule", name,
                "toolSeverity", severity,
                "dependency", fileName == null ? "" : fileName))));
      }
    }
    return findings;
  }

  private static FindingSeverity severityOf(String severity) {
    return switch (severity == null ? "" : severity.trim().toLowerCase()) {
      case "critical" -> FindingSeverity.CRITICAL;
      case "high" -> FindingSeverity.HIGH;
      case "medium" -> FindingSeverity.MEDIUM;
      case "low" -> FindingSeverity.LOW;
      default -> FindingSeverity.INFO;
    };
  }

  private static String relativize(String absolute, Path srcRoot) {
    if (absolute == null || absolute.isBlank()) {
      return null;
    }
    String unified = absolute.replace('\\', '/');
    if (srcRoot != null) {
      String root = srcRoot.toAbsolutePath().toString().replace('\\', '/');
      String prefix = root.endsWith("/") ? root : root + "/";
      if (unified.startsWith(prefix)) {
        return unified.substring(prefix.length());
      }
    }
    if (unified.startsWith("/")) {
      String stripped = unified.replaceAll("^/+", "");
      return stripped.isEmpty() ? null : stripped;
    }
    return unified;
  }
}
