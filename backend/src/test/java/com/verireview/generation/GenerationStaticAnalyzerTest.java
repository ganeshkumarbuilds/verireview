package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.verireview.agent.dto.AiGenerationFilesResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GenerationStaticAnalyzerTest {

  private final GenerationStaticAnalyzer analyzer =
      new GenerationStaticAnalyzer(new ObjectMapper());

  @Test
  void findsSyntaxErrorsInJavaTypescriptPythonJsonAndYaml() {
    List<GenerationStaticAnalyzer.StaticFinding> findings = analyzer.analyze(List.of(
        file("Main.java", "class Broken {"),
        file("App.ts", "const config = {"),
        file("app.py", "def run():\n    return [1"),
        file("package.json", "{\"scripts\":"),
        file("config.yaml", "items: [unterminated\n")));

    assertThat(findings)
        .filteredOn(finding -> finding.category().equals("syntax"))
        .extracting(GenerationStaticAnalyzer.StaticFinding::file)
        .contains("Main.java", "App.ts", "app.py", "package.json", "config.yaml");
  }

  @Test
  void detectsLogicSecurityAndStyleFindingsWithLineNumbers() {
    List<GenerationStaticAnalyzer.StaticFinding> findings = analyzer.analyze(List.of(
        file("app.py", "if user = None:\n    pass\n"),
        file("config.js", "const apiKey = \"abcd1234-secret\";\n"),
        file("src.ts", "const count = 1; \n")));

    assertThat(findings)
        .extracting(GenerationStaticAnalyzer.StaticFinding::category)
        .contains("logic", "security", "style");
    assertThat(findings).allSatisfy(finding -> assertThat(finding.line()).isGreaterThan(0));
    assertThat(findings).extracting(GenerationStaticAnalyzer.StaticFinding::explanation)
        .allSatisfy(explanation -> assertThat(explanation).isNotBlank());
  }

  private static AiGenerationFilesResult.GeneratedFile file(String path, String content) {
    return new AiGenerationFilesResult.GeneratedFile(path, content, null);
  }
}
