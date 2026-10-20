package com.verireview.generation;

import com.verireview.generation.dto.PlanPreviewResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, stack-aware scaffold shown at step 4 of the Generate wizard.
 *
 * <p>This is intentionally not an AI call: the user can review and re-plan
 * without spending tokens or sending a key. The real Planner Agent runs after
 * Generate and its persisted plan replaces this preview.
 */
public final class GenerationScaffoldPreview {

  private GenerationScaffoldPreview() {
  }

  public static PlanPreviewResponse build(
      GenerationBackend backend,
      GenerationFrontend frontend,
      GenerationDatabase database,
      GenerationAuth auth,
      List<GenerationIntegration> integrations) {
    Set<String> directories = new LinkedHashSet<>();
    List<PlanPreviewResponse.PlanFile> files = new ArrayList<>();
    List<String> dependencies = new ArrayList<>();
    List<String> steps = new ArrayList<>();

    steps.add("1. Scaffold the selected stack");
    steps.add("2. Implement the core feature from the description");
    steps.add("3. Add tests and environment placeholders");

    if (backend == GenerationBackend.JAVA_SPRING_BOOT) {
      add(directories, "src/main/java/app", "src/main/java/app/api", "src/test/java/app");
      files.add(new PlanPreviewResponse.PlanFile("pom.xml", "Maven build with Spring Boot"));
      files.add(new PlanPreviewResponse.PlanFile(
          "src/main/java/app/Application.java", "Spring Boot entrypoint"));
      files.add(new PlanPreviewResponse.PlanFile(
          "src/main/java/app/api/HealthController.java", "Health endpoint"));
      files.add(new PlanPreviewResponse.PlanFile(
          "src/test/java/app/ApplicationTest.java", "Context load test"));
      dependencies.addAll(List.of("spring-boot-starter-web", "spring-boot-starter-test"));
    } else if (backend == GenerationBackend.NODEJS) {
      add(directories, "src", "src/routes", "test");
      files.add(new PlanPreviewResponse.PlanFile("package.json", "Node/Express manifest"));
      files.add(new PlanPreviewResponse.PlanFile("src/index.js", "Express app entrypoint"));
      files.add(new PlanPreviewResponse.PlanFile("src/routes/health.js", "Health route"));
      files.add(new PlanPreviewResponse.PlanFile("test/health.test.js", "Route test"));
      dependencies.addAll(List.of("express", "vitest"));
    } else if (backend == GenerationBackend.PYTHON_FASTAPI) {
      add(directories, "app", "app/api", "tests");
      files.add(new PlanPreviewResponse.PlanFile("requirements.txt", "Python dependencies"));
      files.add(new PlanPreviewResponse.PlanFile("app/main.py", "FastAPI entrypoint"));
      files.add(new PlanPreviewResponse.PlanFile("app/api/health.py", "Health router"));
      files.add(new PlanPreviewResponse.PlanFile("tests/test_health.py", "API test"));
      dependencies.addAll(List.of("fastapi", "uvicorn", "pytest"));
    }

    if (frontend == GenerationFrontend.REACT_TYPESCRIPT) {
      add(directories, "frontend", "frontend/src");
      files.add(new PlanPreviewResponse.PlanFile("frontend/package.json", "Vite + React manifest"));
      files.add(new PlanPreviewResponse.PlanFile("frontend/index.html", "HTML entry"));
      files.add(new PlanPreviewResponse.PlanFile("frontend/src/main.tsx", "React bootstrap"));
      files.add(new PlanPreviewResponse.PlanFile("frontend/src/App.tsx", "Root component"));
      dependencies.addAll(List.of("react", "react-dom", "vite", "typescript"));
    } else if (frontend == GenerationFrontend.NEXTJS) {
      add(directories, "frontend", "frontend/app");
      files.add(new PlanPreviewResponse.PlanFile(
          "frontend/package.json", "Next.js manifest"));
      files.add(new PlanPreviewResponse.PlanFile(
          "frontend/app/layout.tsx", "Root layout"));
      files.add(new PlanPreviewResponse.PlanFile(
          "frontend/app/page.tsx", "Home page"));
      dependencies.addAll(List.of("next", "react", "react-dom", "typescript"));
    } else if (frontend == GenerationFrontend.VUE) {
      add(directories, "frontend", "frontend/src");
      files.add(new PlanPreviewResponse.PlanFile("frontend/package.json", "Vite + Vue manifest"));
      files.add(new PlanPreviewResponse.PlanFile("frontend/index.html", "HTML entry"));
      files.add(new PlanPreviewResponse.PlanFile("frontend/src/main.ts", "Vue bootstrap"));
      files.add(new PlanPreviewResponse.PlanFile("frontend/src/App.vue", "Root component"));
      dependencies.addAll(List.of("vue", "vite", "typescript"));
    }

    if (database != GenerationDatabase.NONE) {
      add(directories, "db");
      files.add(new PlanPreviewResponse.PlanFile(
          "db/schema.sql", database.name() + " schema and seed data"));
      dependencies.add(databaseDependency(database));
    }

    if (auth == GenerationAuth.JWT) {
      add(directories, "auth");
      files.add(new PlanPreviewResponse.PlanFile(
          "auth/jwt.md", "JWT login/refresh flow using environment secrets"));
      files.add(new PlanPreviewResponse.PlanFile(
          "auth/login" + backendExtension(backend), "Login handler"));
    } else if (auth == GenerationAuth.OAUTH_GOOGLE) {
      add(directories, "auth");
      files.add(new PlanPreviewResponse.PlanFile(
          "auth/oauth-google.md", "Google OAuth redirect/callback setup"));
      files.add(new PlanPreviewResponse.PlanFile(
          "auth/google-callback" + backendExtension(backend), "OAuth callback handler"));
    }

    for (GenerationIntegration integration : integrations) {
      String slug = integration.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
      String extension = backendExtension(backend);
      String path = backend == GenerationBackend.NONE
          ? "frontend/src/integrations/" + slug + ".ts"
          : "integrations/" + slug + extension;
      add(directories, path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
      files.add(new PlanPreviewResponse.PlanFile(path, integration.note()));
      if (integration == GenerationIntegration.DOCKER_CI) {
        files.add(new PlanPreviewResponse.PlanFile("Dockerfile", "Container build for the app"));
        files.add(new PlanPreviewResponse.PlanFile(
            ".github/workflows/ci.yml", "CI that builds and tests every push"));
      }
    }

    files.add(new PlanPreviewResponse.PlanFile("README.md", "Run, test, and configuration guide"));
    files.add(new PlanPreviewResponse.PlanFile(
        ".env.example", "Every required environment variable, values left blank"));

    String architecture = architecture(backend, frontend, database, auth, integrations);
    return new PlanPreviewResponse(
        architecture,
        List.copyOf(new LinkedHashSet<>(dependencies)),
        List.copyOf(directories),
        "HTTP endpoints follow the requirement; every external service is behind an adapter.",
        List.copyOf(steps),
        List.copyOf(files),
        "Scaffold preview only. The Planner Agent produces the final file plan after you generate.");
  }

  private static void add(Set<String> directories, String... paths) {
    for (String path : paths) {
      if (path != null && !path.isBlank()) {
        directories.add(path);
      }
    }
  }

  private static String databaseDependency(GenerationDatabase database) {
    return switch (database) {
      case POSTGRESQL -> "postgresql-driver";
      case MYSQL -> "mysql-driver";
      case MONGODB -> "mongodb-driver";
      case SQLITE -> "sqlite-driver";
      case NONE -> "";
    };
  }

  private static String backendExtension(GenerationBackend backend) {
    return switch (backend) {
      case JAVA_SPRING_BOOT -> ".java";
      case NODEJS -> ".js";
      case PYTHON_FASTAPI -> ".py";
      case NONE -> ".ts";
    };
  }

  private static String architecture(
      GenerationBackend backend,
      GenerationFrontend frontend,
      GenerationDatabase database,
      GenerationAuth auth,
      List<GenerationIntegration> integrations) {
    StringBuilder text = new StringBuilder();
    text.append("Backend: ").append(label(backend)).append(". ");
    text.append("Frontend: ").append(label(frontend)).append(". ");
    text.append("Database: ").append(label(database)).append(". ");
    text.append("Auth: ").append(auth.name()).append(". ");
    if (integrations.isEmpty()) {
      text.append("No third-party integrations selected.");
    } else {
      text.append("Integrations: ");
      for (int i = 0; i < integrations.size(); i++) {
        if (i > 0) {
          text.append(", ");
        }
        text.append(label(integrations.get(i)));
      }
      text.append('.');
    }
    return text.toString();
  }

  private static String label(Enum<?> value) {
    return value.name().replace('_', ' ');
  }
}
