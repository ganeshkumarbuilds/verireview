package com.verireview.generation;

import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Backend-owned project contract shared by template and AI generation. */
final class GenerationProjectScaffold {

  private GenerationProjectScaffold() {
  }

  static List<AiGenerationPlanResult.PlannedFile> requiredPlan(
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database) {
    List<AiGenerationPlanResult.PlannedFile> files = new ArrayList<>();
    files.add(new AiGenerationPlanResult.PlannedFile(
        "README.md", "Project overview, prerequisites, setup, commands, environment, structure, and troubleshooting"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        ".gitignore", "Generated-project ignore rules for secrets, dependencies, and build output"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        ".env.example", "Environment variable names with empty placeholder values only"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        "Dockerfile", "Multi-stage or minimal production container with a non-root user and health check"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        "docker-compose.yml", "Application service and selected database using environment variables only"));
    files.add(new AiGenerationPlanResult.PlannedFile(
        "LICENSE", "Placeholder license for the generated project"));
    if (backend != GenerationBackend.NONE || frontend != GenerationFrontend.NONE) {
      files.add(new AiGenerationPlanResult.PlannedFile(
          "scripts/run.sh", "Portable local run helper for the selected stack"));
      files.add(new AiGenerationPlanResult.PlannedFile(
          "scripts/test.sh", "Portable local test helper for the selected stack"));
    }
    return List.copyOf(files);
  }

  static List<AiGenerationFilesResult.GeneratedFile> fallbackFiles(
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database,
      String envExample) {
    List<AiGenerationFilesResult.GeneratedFile> files = new ArrayList<>();
    files.add(file("README.md", readme(backend, frontend, database), "markdown"));
    files.add(file(".gitignore", gitignore(), "text"));
    files.add(file(".env.example", envExample, "dotenv"));
    files.add(file("Dockerfile", dockerfile(backend, frontend), "dockerfile"));
    files.add(file("docker-compose.yml", compose(backend, frontend, database), "yaml"));
    files.add(file("LICENSE", "This project is released under a license to be selected by the project owner.\n",
        "text"));
    if (backend != GenerationBackend.NONE || frontend != GenerationFrontend.NONE) {
      files.add(file("scripts/run.sh", runScript(backend, frontend), "shell"));
      files.add(file("scripts/test.sh", testScript(backend, frontend), "shell"));
    }
    return List.copyOf(files);
  }

  static List<AiGenerationFilesResult.GeneratedFile> complete(
      List<AiGenerationFilesResult.GeneratedFile> generated,
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database,
      String envExample) {
    Map<String, AiGenerationFilesResult.GeneratedFile> byPath = new LinkedHashMap<>();
    for (AiGenerationFilesResult.GeneratedFile file : generated) {
      byPath.put(file.path(), file);
    }
    for (AiGenerationFilesResult.GeneratedFile fallback :
        fallbackFiles(backend, frontend, database, envExample)) {
      byPath.putIfAbsent(fallback.path(), fallback);
    }
    return List.copyOf(byPath.values());
  }

  private static AiGenerationFilesResult.GeneratedFile file(
      String path, String content, String language) {
    return new AiGenerationFilesResult.GeneratedFile(path, content, language);
  }

  private static String gitignore() {
    return ".env\n.env.*\n!.env.example\nnode_modules/\ntarget/\n"
        + "__pycache__/\n.pytest_cache/\n*.log\n.DS_Store\n";
  }

  private static String dockerfile(GenerationBackend backend, GenerationFrontend frontend) {
    if (backend == GenerationBackend.JAVA_SPRING_BOOT) {
      return "FROM eclipse-temurin:21-jdk AS build\nWORKDIR /workspace\nCOPY . .\n"
          + "RUN if [ -f mvnw ]; then ./mvnw -B package -DskipTests; "
          + "else mvn -B package -DskipTests; fi\n\n"
          + "FROM eclipse-temurin:21-jre\nRUN useradd --system --create-home appuser\n"
          + "WORKDIR /app\nCOPY --from=build /workspace/target/*.jar app.jar\n"
          + "USER appuser\nEXPOSE 8080\nHEALTHCHECK --interval=30s CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1\n"
          + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n";
    }
    if (backend == GenerationBackend.PYTHON_FASTAPI) {
      return "FROM python:3.12-slim\nWORKDIR /app\nCOPY . .\n"
          + "RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home appuser\n"
          + "USER appuser\nEXPOSE 8000\nHEALTHCHECK --interval=30s CMD python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')\"\n"
          + "CMD [\"uvicorn\", \"src.main:app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]\n";
    }
    if (backend == GenerationBackend.NODEJS || frontend != GenerationFrontend.NONE) {
      return "FROM node:20-alpine AS build\nWORKDIR /app\nCOPY package*.json ./\nRUN npm ci\nCOPY . .\nRUN npm run build\n\n"
          + "FROM node:20-alpine\nWORKDIR /app\nRUN addgroup -S app && adduser -S app -G app\nCOPY --from=build /app .\nUSER app\nEXPOSE 3000\nHEALTHCHECK --interval=30s CMD wget -qO- http://127.0.0.1:3000/ || exit 1\nCMD [\"npm\", \"run\", \"start\"]\n";
    }
    return "FROM alpine:3.20\nRUN addgroup -S app && adduser -S app -G app\n"
        + "WORKDIR /app\nCOPY . .\nUSER app\nHEALTHCHECK CMD test -f README.md\nCMD [\"sh\", \"-c\", \"echo Project files are ready; sleep infinity\"]\n";
  }

  private static String compose(
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database) {
    String port = backend == GenerationBackend.PYTHON_FASTAPI ? "8000"
        : (backend == GenerationBackend.NODEJS || frontend != GenerationFrontend.NONE ? "3000" : "8080");
    String db = switch (database) {
      case POSTGRESQL -> "  database:\n    image: postgres:16-alpine\n    environment:\n      POSTGRES_DB: ${DATABASE_NAME:-app}\n      POSTGRES_USER: ${DATABASE_USERNAME:-app}\n      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-change-me}\n";
      case MYSQL -> "  database:\n    image: mysql:8\n    environment:\n      MYSQL_DATABASE: ${DATABASE_NAME:-app}\n      MYSQL_USER: ${DATABASE_USERNAME:-app}\n      MYSQL_PASSWORD: ${DATABASE_PASSWORD:-change-me}\n      MYSQL_ROOT_PASSWORD: ${DATABASE_ROOT_PASSWORD:-change-me}\n";
      case MONGODB -> "  database:\n    image: mongo:7\n    environment:\n      MONGO_INITDB_DATABASE: ${DATABASE_NAME:-app}\n";
      default -> "";
    };
    return "services:\n  app:\n    build: .\n    env_file: .env\n    ports:\n      - \"${PORT:-" + port + "}:" + port + "\"\n"
        + (db.isEmpty() ? "" : "    depends_on:\n      - database\n") + db;
  }

  private static String runScript(GenerationBackend backend, GenerationFrontend frontend) {
    if (backend == GenerationBackend.JAVA_SPRING_BOOT) {
      return "#!/usr/bin/env sh\nset -eu\nif [ -x ./mvnw ]; then exec ./mvnw spring-boot:run; fi\nexec mvn spring-boot:run\n";
    }
    if (backend == GenerationBackend.PYTHON_FASTAPI) {
      return "#!/usr/bin/env sh\nset -eu\nexec uvicorn src.main:app --reload\n";
    }
    return "#!/usr/bin/env sh\nset -eu\nexec npm run dev\n";
  }

  private static String testScript(GenerationBackend backend, GenerationFrontend frontend) {
    if (backend == GenerationBackend.JAVA_SPRING_BOOT) {
      return "#!/usr/bin/env sh\nset -eu\nif [ -x ./mvnw ]; then exec ./mvnw test; fi\nexec mvn test\n";
    }
    if (backend == GenerationBackend.PYTHON_FASTAPI) {
      return "#!/usr/bin/env sh\nset -eu\nexec pytest -v\n";
    }
    return "#!/usr/bin/env sh\nset -eu\nexec npm test\n";
  }

  private static String readme(
      GenerationBackend backend, GenerationFrontend frontend, GenerationDatabase database) {
    return "# Generated project\n\n"
        + "A generated " + backend.name() + " project"
        + (frontend == GenerationFrontend.NONE ? "" : " with " + frontend.name() + " frontend") + ".\n\n"
        + "## Tech stack\n\n- Backend: " + backend.name() + "\n- Frontend: " + frontend.name()
        + "\n- Database: " + database.name() + "\n\n"
        + "## Prerequisites\n\nInstall the selected runtime, its package manager, and Docker. Copy `.env.example` to `.env` and provide local values.\n\n"
        + "## Install\n\nInstall dependencies using the selected stack's package manager.\n\n"
        + "## Run\n\nRun locally with `scripts/run.sh` or `docker compose up --build`.\n\n"
        + "## Test\n\nRun `scripts/test.sh`.\n\n"
        + "## Build\n\nBuild the image with `docker compose build`.\n\n"
        + "## Environment variables\n\n| Name | Purpose |\n| --- | --- |\n"
        + "| PORT | Application port |\n| DATABASE_URL | Optional database connection URL |\n"
        + "| DATABASE_PASSWORD | Optional database password |\n| AI_API_KEY | Optional provider key |\n\n"
        + "Values are intentionally blank in `.env.example` and must be supplied locally.\n\n"
        + "## Project structure\n\n"
        + "- `src/` and `tests/` — application and test sources where the selected stack uses them\n"
        + "- `frontend/` — frontend sources when a frontend was selected\n"
        + "- `scripts/` — local run and test commands\n\n"
        + "## Troubleshooting\n\nCheck Docker logs with `docker compose logs app`, verify `.env`, and confirm the selected port is available.\n";
  }
}
