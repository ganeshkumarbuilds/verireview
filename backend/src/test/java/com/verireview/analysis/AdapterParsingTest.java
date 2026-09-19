package com.verireview.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.verireview.review.FindingCategory;
import com.verireview.review.FindingSeverity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden fixtures per tool: rule IDs, file/line refs, severity and category
 * mapping. Malformed reports yield empty lists, never exceptions.
 */
class AdapterParsingTest {

  @TempDir
  Path temp;

  private static final String CHECKSTYLE_XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <checkstyle version="10.12.7">
      <file name="/src/Main.java">
      <error line="3" column="80" severity="error"
        message="Line is longer than 80 characters (found 101)."
        source="com.puppycrawl.tools.checkstyle.checks.sizes.LineLengthCheck"/>
      <error line="7" severity="warning" message="Unused import."
        source="com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck"/>
      </file>
      </checkstyle>
      """;

  private static final String PMD_XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.5.0">
      <file name="/src/Main.java">
      <violation beginline="12" endline="12" rule="SystemPrintln" ruleset="Best Practices"
        priority="2">Usage of System.out/err</violation>
      <violation beginline="20" endline="22" rule="AvoidDuplicateLiterals" ruleset="Security"
        priority="4">Duplicate string literal</violation>
      </file>
      </pmd>
      """;

  private static final String SPOTBUGS_XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <BugCollection>
      <BugInstance type="NP_NULL_ON_SOME_PATH" priority="2" rank="7" category="STYLE">
        <Class classname="com.example.Foo"/>
        <Method name="bar"/>
        <SourceLine classname="com.example.Foo" primary="true"
          sourcepath="com/example/Foo.java" start="41" end="41"/>
        <LongMessage>Possible null pointer dereference in method</LongMessage>
      </BugInstance>
      </BugCollection>
      """;

  private static final String DEPCHECK_JSON = """
      {"dependencies": [{
        "fileName": "spring-core-5.3.0.jar",
        "filePath": "/src/libs/spring-core-5.3.0.jar",
        "vulnerabilities": [{
          "name": "CVE-2020-5421",
          "severity": "High",
          "description": "Spring Framework RFD attack"
        }]
      }]}
      """;

  @Test
  void checkstyleGolden() throws Exception {
    Path srcRoot = temp.resolve("src");
    Path report = write("checkstyle.xml", withRoot(CHECKSTYLE_XML, srcRoot));
    List<NormalizedFinding> findings =
        CheckstyleParser.parse(report, srcRoot);

    assertThat(findings).hasSize(2);
    NormalizedFinding first = findings.get(0);
    assertThat(first.analyzer()).isEqualTo("checkstyle");
    assertThat(first.rule()).isEqualTo("LineLength");
    assertThat(first.category()).isEqualTo(FindingCategory.STYLE);
    assertThat(first.severity()).isEqualTo(FindingSeverity.MEDIUM);
    assertThat(first.filePath()).isEqualTo("Main.java");
    assertThat(first.lineStart()).isEqualTo(3);
    assertThat(findings.get(1).severity()).isEqualTo(FindingSeverity.LOW);
    assertThat(first.evidenceJson()).contains(ToolVersions.CHECKSTYLE);
  }

  @Test
  void pmdGoldenWithNamespace() throws Exception {
    Path srcRoot = temp.resolve("src");
    Path report = write("pmd.xml", withRoot(PMD_XML, srcRoot));
    List<NormalizedFinding> findings = PmdParser.parse(report, srcRoot);

    assertThat(findings).hasSize(2);
    assertThat(findings.get(0).rule()).isEqualTo("SystemPrintln");
    assertThat(findings.get(0).severity()).isEqualTo(FindingSeverity.HIGH);
    assertThat(findings.get(0).category()).isEqualTo(FindingCategory.CODE_QUALITY);
    assertThat(findings.get(1).category()).isEqualTo(FindingCategory.SECURITY);
    assertThat(findings.get(1).severity()).isEqualTo(FindingSeverity.LOW);
    assertThat(findings.get(0).lineEnd()).isEqualTo(12);
  }

  @Test
  void spotbugsGolden() throws Exception {
    Path report = write("spotbugs.xml", SPOTBUGS_XML);
    List<NormalizedFinding> findings = SpotbugsParser.parse(report, Path.of("/src"));

    assertThat(findings).hasSize(1);
    NormalizedFinding finding = findings.get(0);
    assertThat(finding.rule()).isEqualTo("NP_NULL_ON_SOME_PATH");
    assertThat(finding.category()).isEqualTo(FindingCategory.STYLE);
    assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
    assertThat(finding.filePath()).isEqualTo("com/example/Foo.java");
    assertThat(finding.lineStart()).isEqualTo(41);
    assertThat(finding.title()).contains("Foo");
  }

  @Test
  void dependencyCheckGolden() throws Exception {
    Path srcRoot = temp.resolve("src");
    Path report = write("depcheck.json", withRoot(DEPCHECK_JSON, srcRoot));
    List<NormalizedFinding> findings =
        DependencyCheckParser.parse(report, srcRoot);

    assertThat(findings).hasSize(1);
    NormalizedFinding finding = findings.get(0);
    assertThat(finding.rule()).isEqualTo("CVE-2020-5421");
    assertThat(finding.category()).isEqualTo(FindingCategory.DEPENDENCY);
    assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
    assertThat(finding.filePath()).isEqualTo("libs/spring-core-5.3.0.jar");
  }

  @Test
  void malformedReportsYieldEmptyLists() throws Exception {
    Path broken = write("broken.xml", "<checkstyle><file>");
    assertThat(CheckstyleParser.parse(broken, Path.of("/src"))).isEmpty();
    assertThat(PmdParser.parse(broken, Path.of("/src"))).isEmpty();
    assertThat(SpotbugsParser.parse(broken, Path.of("/src"))).isEmpty();
    Path brokenJson = write("broken.json", "{nope");
    assertThat(DependencyCheckParser.parse(brokenJson, Path.of("/src"))).isEmpty();
    Path missing = temp.resolve("missing.xml");
    assertThat(CheckstyleParser.parse(missing, Path.of("/src"))).isEmpty();
    assertThat(DependencyCheckParser.parse(missing, Path.of("/src"))).isEmpty();
  }

  @Test
  void toolVersionsArePinned() {
    assertThat(ToolVersions.CHECKSTYLE).isEqualTo("10.12.7");
    assertThat(ToolVersions.PMD).isEqualTo("7.5.0");
    assertThat(ToolVersions.SPOTBUGS).isEqualTo("4.8.6");
  }

  @Test
  void fingerprintIsStableAndDiscriminating() {
    String a = Fingerprint.of("checkstyle", "LineLength", "Main.java", 3, "too long");
    String b = Fingerprint.of("checkstyle", "LineLength", "Main.java", 3, "too long");
    String otherLine = Fingerprint.of("checkstyle", "LineLength", "Main.java", 4, "too long");
    String otherTool = Fingerprint.of("pmd", "LineLength", "Main.java", 3, "too long");
    assertThat(a).isEqualTo(b);
    assertThat(a).hasSize(64);
    assertThat(otherLine).isNotEqualTo(a);
    assertThat(otherTool).isNotEqualTo(a);
  }

  private Path write(String name, String content) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  /** Rewrites fixture "/src/…" paths onto a real (absolute) snapshot root. */
  private static String withRoot(String xml, Path srcRoot) {
    return xml.replace("/src/",
        srcRoot.toAbsolutePath().toString().replace('\\', '/') + "/");
  }
}
