package com.verireview.generation;

import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic starter templates for {@link GenerationAiProvider#NONE} runs.
 *
 * <p>No AI key, no network, no dependencies: plain-Java sources compiled with
 * {@code javac} and checked with {@code java -ea}, so the sandbox (read-only
 * mount, no network) can genuinely build and test them. The template flow
 * reuses the exact same persistence, build-evidence, verification, review,
 * and download-gate machinery as AI runs — only plan/files authorship and
 * the verdict derivation differ, and both are labeled {@code template}.
 */
public final class GenerationTemplates {

  /** Prompt/version label recorded on template agent executions. */
  public static final String TEMPLATE_VERSION = "template/v1";

  /** Build+test command for template workspaces (writes to /tmp: mount is ro). */
  public static final String BUILD_COMMAND =
      "mkdir -p /tmp/gen-out && find src -name \"*.java\" > /tmp/gen-sources"
          + " && javac -d /tmp/gen-out @/tmp/gen-sources"
          + " && java -ea -cp /tmp/gen-out app.AppTest";

  private GenerationTemplates() {
  }

  public static AiGenerationPlanResult plan(String generationId,
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database) {
    List<AiGenerationPlanResult.PlannedFile> files = new ArrayList<>();
    files.add(new AiGenerationPlanResult.PlannedFile(
        "src/main/java/app/App.java", "Template service entrypoint with greeting logic"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        "src/test/java/app/AppTest.java", "Dependency-free checks run with java -ea"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        "README.md", "Template layout, build, and configuration notes"));
    List<String> directories = new ArrayList<>(List.of("src/main/java/app", "src/test/java/app"));
    if (frontend == GenerationFrontend.REACT_TYPESCRIPT) {
      files.add(new AiGenerationPlanResult.PlannedFile(
          "frontend/package.json", "React starter manifest (needs npm outside the sandbox)"));
      files.add(new AiGenerationPlanResult.PlannedFile(
          "frontend/index.html", "React entry page"));
      files.add(new AiGenerationPlanResult.PlannedFile(
          "frontend/src/main.tsx", "React bootstrap"));
      files.add(new AiGenerationPlanResult.PlannedFile(
          "frontend/src/App.tsx", "Starter component"));
      directories.add("frontend/src");
    }
    String architecture = "Dependency-free Java starter (no-AI template)"
        + " for " + backend.name()
        + (frontend == GenerationFrontend.REACT_TYPESCRIPT ? " with a React starter" : "")
        + (database == GenerationDatabase.NONE
            ? "; no database configured"
            : "; database " + database.name() + " configured through environment variables");
    return new AiGenerationPlanResult(
        generationId,
        List.copyOf(files),
        "Deterministic template plan",
        new AiGenerationPlanResult.PlanSections(
            architecture,
            List.of(),
            List.copyOf(directories),
            "",
            List.of("Compile with javac", "Run checks with java -ea", "Configure via environment")));
  }

  public static List<AiGenerationFilesResult.GeneratedFile> files(
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database) {
    List<AiGenerationFilesResult.GeneratedFile> files = new ArrayList<>();
    files.add(new AiGenerationFilesResult.GeneratedFile(
        "src/main/java/app/App.java", appJava(database), "java"));
    files.add(new AiGenerationFilesResult.GeneratedFile(
        "src/test/java/app/AppTest.java", appTestJava(), "java"));
    files.add(new AiGenerationFilesResult.GeneratedFile(
        "README.md", readmeMd(backend, frontend, database), "markdown"));
    if (frontend == GenerationFrontend.REACT_TYPESCRIPT) {
      files.add(new AiGenerationFilesResult.GeneratedFile(
          "frontend/package.json", reactPackageJson(), "json"));
      files.add(new AiGenerationFilesResult.GeneratedFile(
          "frontend/index.html", reactIndexHtml(), "html"));
      files.add(new AiGenerationFilesResult.GeneratedFile(
          "frontend/src/main.tsx", reactMainTsx(), "typescript"));
      files.add(new AiGenerationFilesResult.GeneratedFile(
          "frontend/src/App.tsx", reactAppTsx(), "typescript"));
    }
    return List.copyOf(files);
  }

  private static String appJava(GenerationDatabase database) {
    String dbNote = database == GenerationDatabase.NONE
        ? ""
        : "\n    String dbUrl = System.getenv().getOrDefault(\"DATABASE_URL\", \"not-configured\");\n"
            + "    System.out.println(\"Database: \" + \"" + database.name() + " @ \" + dbUrl);";
    return "package app;\n"
        + "\n"
        + "/** Minimal VeriReview template service (no-AI starter). */\n"
        + "public class App {\n"
        + "\n"
        + "  public static String greet(String name) {\n"
        + "    String who = name == null || name.isBlank() ? \"world\" : name.trim();\n"
        + "    return \"Hello, \" + who + \"!\";\n"
        + "  }\n"
        + "\n"
        + "  public static void main(String[] args) {\n"
        + "    String port = System.getenv().getOrDefault(\"PORT\", \"8080\");\n"
        + "    System.out.println(greet(null) + \" Listening on \" + port + \" (template).\");"
        + dbNote + "\n"
        + "  }\n"
        + "}\n";
  }

  private static String appTestJava() {
    return "package app;\n"
        + "\n"
        + "/** Dependency-free checks, run with {@code java -ea} (no JUnit needed). */\n"
        + "public class AppTest {\n"
        + "\n"
        + "  public static void main(String[] args) {\n"
        + "    check(App.greet(null).equals(\"Hello, world!\"), \"default greeting\");\n"
        + "    check(App.greet(\"  Ada \").equals(\"Hello, Ada!\"), \"trims names\");\n"
        + "    check(!App.greet(\"Bo\").isBlank(), \"non-blank greeting\");\n"
        + "    System.out.println(\"All template checks passed.\");\n"
        + "  }\n"
        + "\n"
        + "  private static void check(boolean condition, String name) {\n"
        + "    if (!condition) {\n"
        + "      System.out.println(\"FAILED: \" + name);\n"
        + "      System.exit(1);\n"
        + "    }\n"
        + "    System.out.println(\"PASS: \" + name);\n"
        + "  }\n"
        + "}\n";
  }

  private static String readmeMd(GenerationBackend backend, GenerationFrontend frontend,
      GenerationDatabase database) {
    return "# VeriReview template starter (no AI key)\n"
        + "\n"
        + "Deterministic starter for `" + backend.name() + "`"
        + (frontend == GenerationFrontend.REACT_TYPESCRIPT ? " with a React starter" : "")
        + ".\n"
        + "\n"
        + "## Layout\n"
        + "\n"
        + "- `src/main/java/app/App.java` — service entrypoint\n"
        + "- `src/test/java/app/AppTest.java` — dependency-free checks\n"
        + (frontend == GenerationFrontend.REACT_TYPESCRIPT
            ? "- `frontend/` — React starter (needs `npm` outside the sandbox)\n" : "")
        + "\n"
        + "## Build and test (no network, no dependencies)\n"
        + "\n"
        + "```sh\n"
        + "mkdir -p /tmp/gen-out && find src -name \"*.java\" > /tmp/gen-sources \\\n"
        + "  && javac -d /tmp/gen-out @/tmp/gen-sources \\\n"
        + "  && java -ea -cp /tmp/gen-out app.AppTest\n"
        + "```\n"
        + "\n"
        + "## Configuration (environment only — never commit secrets)\n"
        + "\n"
        + "- `PORT` — listen port (default `8080`)\n"
        + (database == GenerationDatabase.NONE
            ? "- No database configured for this project.\n"
            : "- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` — "
                + database.name() + " connection (see `.env.example` in the download).\n")
        + "\n"
        + "Graduate to a full AI-planned project from the Generate page when ready.\n";
  }

  private static String reactPackageJson() {
    return "{\n"
        + "  \"name\": \"verireview-app\",\n"
        + "  \"private\": true,\n"
        + "  \"version\": \"0.1.0\",\n"
        + "  \"type\": \"module\",\n"
        + "  \"scripts\": {\n"
        + "    \"dev\": \"vite\",\n"
        + "    \"build\": \"vite build\"\n"
        + "  },\n"
        + "  \"dependencies\": {\n"
        + "    \"react\": \"^18.3.1\",\n"
        + "    \"react-dom\": \"^18.3.1\"\n"
        + "  },\n"
        + "  \"devDependencies\": {\n"
        + "    \"vite\": \"^6.0.0\"\n"
        + "  }\n"
        + "}\n";
  }

  private static String reactIndexHtml() {
    return "<!doctype html>\n"
        + "<html lang=\"en\">\n"
        + "  <head>\n"
        + "    <meta charset=\"UTF-8\" />\n"
        + "    <title>VeriReview template app</title>\n"
        + "  </head>\n"
        + "  <body>\n"
        + "    <div id=\"root\"></div>\n"
        + "    <script type=\"module\" src=\"/src/main.tsx\"></script>\n"
        + "  </body>\n"
        + "</html>\n";
  }

  private static String reactMainTsx() {
    return "import React from 'react';\n"
        + "import { createRoot } from 'react-dom/client';\n"
        + "import { App } from './App';\n"
        + "\n"
        + "createRoot(document.getElementById('root')!).render(\n"
        + "  <React.StrictMode>\n"
        + "    <App />\n"
        + "  </React.StrictMode>,\n"
        + ");\n";
  }

  private static String reactAppTsx() {
    return "export function App() {\n"
        + "  return (\n"
        + "    <main style={{ fontFamily: 'system-ui', padding: 24 }}>\n"
        + "      <h1>VeriReview template app</h1>\n"
        + "      <p>Starter component — run <code>npm install</code> then <code>npm run dev</code>.</p>\n"
        + "    </main>\n"
        + "  );\n"
        + "}\n";
  }
}
