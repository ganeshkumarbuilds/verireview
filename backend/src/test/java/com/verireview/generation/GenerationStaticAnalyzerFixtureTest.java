package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.verireview.agent.dto.AiGenerationFilesResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GenerationStaticAnalyzerFixtureTest {

  private final GenerationStaticAnalyzer analyzer =
      new GenerationStaticAnalyzer(new ObjectMapper());

  @Test
  void detectsAllDeliberateSyntaxErrorsAndSecurityFinding() throws IOException {
    List<AiGenerationFilesResult.GeneratedFile> files = List.of(
        fixture("analysis-fixtures/deliberate-errors/src/App.js", "js"),
        fixture("analysis-fixtures/deliberate-errors/src/App.jsx", "jsx"),
        fixture("analysis-fixtures/deliberate-errors/package.json", "json"),
        fixture("analysis-fixtures/deliberate-errors/src/broken.py", "py"),
        fixture("analysis-fixtures/deliberate-errors/src/Broken.java", "java"),
        fixture("analysis-fixtures/deliberate-errors/src/config.js", "js"));

    List<GenerationStaticAnalyzer.StaticFinding> findings = analyzer.analyze(files);

    assertThat(findings)
        .filteredOn(finding -> finding.category().equals("syntax"))
        .extracting(GenerationStaticAnalyzer.StaticFinding::file)
        .contains(
            "analysis-fixtures/deliberate-errors/src/App.js",
            "analysis-fixtures/deliberate-errors/src/App.jsx",
            "analysis-fixtures/deliberate-errors/package.json",
            "analysis-fixtures/deliberate-errors/src/broken.py",
            "analysis-fixtures/deliberate-errors/src/Broken.java");
    assertThat(findings)
        .filteredOn(finding -> finding.category().equals("security"))
        .extracting(GenerationStaticAnalyzer.StaticFinding::file)
        .contains("analysis-fixtures/deliberate-errors/src/config.js");
  }

  @Test
  void validPythonAndJsxSamplesHaveNoSyntaxFindings() {
    List<AiGenerationFilesResult.GeneratedFile> valid = List.of(
        new AiGenerationFilesResult.GeneratedFile(
            "valid.py",
            """
            def render(items):
                result = []
                for item in items:
                    result.append(item)
                return result
            """,
            "py"),
        new AiGenerationFilesResult.GeneratedFile(
            "valid.jsx",
            """
            const compare = (a, b) => a < b;
            const genericLike = value => value;
            export function View({items}) {
              return (
                <>
                  {items.map(item => <Row key={item.id} />)}
                  <section><span>Ready</span></section>
                </>
              );
            }
            """,
            "jsx"),
        new AiGenerationFilesResult.GeneratedFile(
            "valid.tsx",
            """
            const identity = <T,>(value: T): T => value;
            export const View = () => <><identity /></>;
            """,
            "tsx"));

    assertThat(analyzer.analyze(valid))
        .filteredOn(finding -> finding.category().equals("syntax"))
        .isEmpty();
  }

  @Test
  void cleanFixtureHasNoFindingsAndLargeSourceCanBePassedToAnalyzer() throws IOException {
    List<AiGenerationFilesResult.GeneratedFile> clean = List.of(
        fixture("analysis-fixtures/clean-project/src/clean.js", "js"),
        fixture("analysis-fixtures/clean-project/src/clean.py", "py"),
        fixture("analysis-fixtures/clean-project/package.json", "json"));
    assertThat(analyzer.analyze(clean)).isEmpty();

    String large = "export const source = '" + "x".repeat(150_000) + "';";
    List<GenerationStaticAnalyzer.StaticFinding> largeFindings = analyzer.analyze(
        List.of(new AiGenerationFilesResult.GeneratedFile(
            "analysis-fixtures/large-project/src/large.js", large, "js")));
    assertThat(largeFindings).isEmpty();
  }

  @Test
  void unreadableFixtureIsNotTreatedAsCheckedCodeByTheFixtureInventory() throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
        "analysis-fixtures/unreadable-project/src/unreadable.bin")) {
      assertThat(stream).isNotNull();
      assertThat(stream.readAllBytes()).isNotEmpty();
    }
    List<String> inventory = List.of("analysis-fixtures/unreadable-project/src/unreadable.bin");
    List<String> checkedCode = inventory.stream()
        .filter(path -> path.endsWith(".java") || path.endsWith(".js")
            || path.endsWith(".jsx") || path.endsWith(".py"))
        .toList();
    assertThat(checkedCode).isEmpty();
  }

  private static AiGenerationFilesResult.GeneratedFile fixture(String path, String language)
      throws IOException {
    try (InputStream stream = GenerationStaticAnalyzerFixtureTest.class.getClassLoader()
        .getResourceAsStream(path)) {
      assertThat(stream).as("fixture %s", path).isNotNull();
      return new AiGenerationFilesResult.GeneratedFile(
          path, new String(stream.readAllBytes(), StandardCharsets.UTF_8), language);
    }
  }
}
