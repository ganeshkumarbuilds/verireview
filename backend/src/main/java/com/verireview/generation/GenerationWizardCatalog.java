package com.verireview.generation;

import com.verireview.generation.dto.GenerationAnswerRequest;
import com.verireview.generation.dto.GenerationQuestion;
import com.verireview.generation.dto.GenerationQuestionType;
import com.verireview.generation.dto.GenerationQuestionsRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deterministic wizard catalog. The backend — not the frontend and not an
 * LLM — decides which clarifying questions exist for the chosen stack, and
 * validates that the user answered them. Nothing is defaulted: a missing
 * answer becomes a 400 that names the question id.
 *
 * <p>Secrets are identified here and filtered by {@link GenerationWizardService}
 * before persistence. This class never logs or stores values.
 */
public final class GenerationWizardCatalog {

  public static final String DATABASE_TYPE = "database.type";
  public static final String DATABASE_CONNECTION = "database.connection";
  public static final String AUTH_METHOD = "auth.method";
  public static final String AI_FEATURES = "ai.features";
  public static final String ENGINE = "generation.engine";
  public static final String MODEL = "generation.model";
  public static final String API_KEY = "generation.apiKey";
  public static final String BASE_URL = "generation.baseUrl";
  public static final String DEPLOYMENT_TARGET = "deployment.target";
  public static final String INTEGRATIONS = "integrations";

  private static final Set<String> SECRET_IDS = Set.of(API_KEY, DATABASE_CONNECTION);

  private GenerationWizardCatalog() {
  }

  public static Set<String> secretIds() {
    return SECRET_IDS;
  }

  public static boolean isSecret(String questionId) {
    return SECRET_IDS.contains(questionId);
  }

  /** Every question is derived from the explicit steps 1–2 choices. */
  public static List<GenerationQuestion> questions(GenerationQuestionsRequest project) {
    requireStack(project);
    List<GenerationQuestion> questions = new ArrayList<>();
    questions.add(new GenerationQuestion(
        DATABASE_TYPE,
        "Which database should the project use?",
        "Pick the database engine. Choose None for no database, or Ask me later "
            + "and the plan will leave the connection unconfigured.",
        GenerationQuestionType.CHOICE,
        true,
        true,
        false,
        List.of(
            option("POSTGRESQL", "PostgreSQL"),
            option("MYSQL", "MySQL"),
            option("MONGODB", "MongoDB"),
            option("SQLITE", "SQLite"),
            option("NONE", "None — no database"))));
    questions.add(new GenerationQuestion(
        DATABASE_CONNECTION,
        "What are the database connection details?",
        "A connection URL or host, port, database name, and username. This value "
            + "is treated as secret, used only while preparing the plan, and never "
            + "stored or sent to the code-generation service.",
        GenerationQuestionType.TEXT,
        false,
        true,
        true,
        List.of()));
    questions.add(new GenerationQuestion(
        AUTH_METHOD,
        "How should users authenticate?",
        "Authentication is only scaffolded for a backend project.",
        GenerationQuestionType.CHOICE,
        true,
        true,
        false,
        List.of(
            option("JWT", "JWT email + password"),
            option("OAUTH_GOOGLE", "Google OAuth"),
            option("NONE", "No authentication"))));
    questions.add(new GenerationQuestion(
        AI_FEATURES,
        "Does the product itself include AI features?",
        "This is about the app you are building, not about the code generator.",
        GenerationQuestionType.YES_NO,
        true,
        false,
        false,
        List.of()));
    questions.add(new GenerationQuestion(
        ENGINE,
        "Which generation engine should write the code?",
        "OpenRouter and custom OpenAI-compatible endpoints need a key. Template "
            + "is deterministic and currently supports Spring Boot + React only.",
        GenerationQuestionType.CHOICE,
        true,
        false,
        false,
        List.of(
            option("OPENROUTER", "OpenRouter"),
            option("CUSTOM", "Custom (OpenAI-compatible)"),
            option("TEMPLATE", "Template — no AI key"))));
    questions.add(new GenerationQuestion(
        MODEL,
        "Which generation model?",
        "Required unless the template engine is selected.",
        GenerationQuestionType.TEXT,
        false,
        false,
        false,
        List.of()));
    questions.add(new GenerationQuestion(
        API_KEY,
        "Generation engine API key",
        "Sent once and held in memory for the run only. Never stored or returned.",
        GenerationQuestionType.TEXT,
        false,
        false,
        true,
        List.of()));
    questions.add(new GenerationQuestion(
        BASE_URL,
        "Custom OpenAI-compatible base URL",
        "Required when the custom engine is selected.",
        GenerationQuestionType.TEXT,
        false,
        false,
        false,
        List.of()));
    questions.add(new GenerationQuestion(
        DEPLOYMENT_TARGET,
        "Where will this deploy?",
        "Adds the matching deployment files to the plan.",
        GenerationQuestionType.CHOICE,
        true,
        true,
        false,
        List.of(
            option("LOCAL", "Local machine"),
            option("DOCKER", "Docker / Compose"),
            option("CLOUD", "Cloud container platform"))));
    questions.add(new GenerationQuestion(
        INTEGRATIONS,
        "Which third-party integrations should be scaffolded?",
        "Comma-separated. Each integration adds an adapter and an environment "
            + "placeholder — never a credential.",
        GenerationQuestionType.TEXT,
        false,
        true,
        false,
        List.of(
            option("STRIPE", "Stripe"),
            option("RAZORPAY", "Razorpay"),
            option("EMAIL", "Email"),
            option("FILE_STORAGE_S3", "S3 file storage"),
            option("AI_LLM", "AI/LLM call adapter"),
            option("MAPS", "Maps"),
            option("ANALYTICS", "Analytics"),
            option("DOCKER_CI", "Docker / CI"))));
    return List.copyOf(questions);
  }

  /**
   * Validates answers against the generated questions and resolves the
   * explicit stack values. Never fabricates an answer: any missing required
   * question is returned in {@link Resolution#missing()} for the caller to
   * reject.
   */
  public static Resolution resolve(
      GenerationQuestionsRequest project, List<GenerationAnswerRequest> answers) {
    List<GenerationQuestion> questions = questions(project);
    Map<String, GenerationAnswerRequest> byId = new LinkedHashMap<>();
    for (GenerationAnswerRequest answer : answers == null ? List.<GenerationAnswerRequest>of() : answers) {
      String id = answer.questionId().trim();
      if (questions.stream().noneMatch(question -> question.id().equals(id))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Unknown question id: " + id);
      }
      byId.put(id, answer);
    }

    List<String> missing = new ArrayList<>();
    Map<String, String> values = new LinkedHashMap<>();
    for (GenerationQuestion question : questions) {
      GenerationAnswerRequest answer = byId.get(question.id());
      boolean skipped = answer != null && answer.skipped();
      String value = answer == null ? null : answer.value();
      boolean blank = value == null || value.isBlank();
      if (question.required() && (answer == null || (skipped && !question.allowSkip())
          || (!skipped && blank))) {
        missing.add(question.id());
        continue;
      }
      if (skipped && !question.allowSkip()) {
        missing.add(question.id());
        continue;
      }
      if (!skipped && value != null) {
        String trimmed = value.trim();
        if (question.type() == GenerationQuestionType.CHOICE) {
          trimmed = question.options().stream()
              .map(GenerationQuestion.QuestionOption::value)
              .filter(candidate -> candidate.equalsIgnoreCase(value.trim()))
              .findFirst()
              .orElseThrow(() -> new ResponseStatusException(
                  HttpStatus.BAD_REQUEST, "Unknown choice for " + question.id() + ": " + value));
        }
        if (question.type() == GenerationQuestionType.YES_NO) {
          if (!"YES".equalsIgnoreCase(trimmed) && !"NO".equalsIgnoreCase(trimmed)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Answer " + question.id() + " with YES or NO");
          }
          trimmed = trimmed.toUpperCase(Locale.ROOT);
        }
        values.put(question.id(), trimmed);
      }
    }

    String engine = values.get(ENGINE);
    if (engine == null || engine.isBlank()) {
      if (!missing.contains(ENGINE)) {
        missing.add(ENGINE);
      }
    } else if (!"TEMPLATE".equals(engine)) {
      require(values, MODEL, missing);
      require(values, API_KEY, missing);
      if ("CUSTOM".equals(engine)) {
        require(values, BASE_URL, missing);
      }
    }

    // A selected database needs either connection details or an explicit
    // "Ask me later". A blank optional field must not silently become that
    // choice, because then the backend would be assuming an answer.
    String databaseChoice = values.get(DATABASE_TYPE);
    if (databaseChoice != null && !"NONE".equalsIgnoreCase(databaseChoice)) {
      GenerationAnswerRequest connection = byId.get(DATABASE_CONNECTION);
      boolean explicitlyLater = connection != null && connection.skipped();
      boolean hasConnection = connection != null && connection.value() != null
          && !connection.value().isBlank();
      if (!explicitlyLater && !hasConnection && !missing.contains(DATABASE_CONNECTION)) {
        missing.add(DATABASE_CONNECTION);
      }
    }

    if (!missing.isEmpty()) {
      return new Resolution(values, null, null, List.of(), true, List.copyOf(missing));
    }

    GenerationDatabase database = GenerationDatabase.NONE;
    // A skipped database question is an explicit "configure later"; choosing
    // None is a different, final answer and must not become a deferred one.
    boolean databaseConfigureLater = values.get(DATABASE_TYPE) == null;
    String databaseAnswer = values.get(DATABASE_TYPE);
    if (databaseAnswer != null && !"NONE".equalsIgnoreCase(databaseAnswer)) {
      try {
        database = GenerationDatabase.valueOf(databaseAnswer.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Unknown database choice: " + databaseAnswer);
      }
      String connection = values.get(DATABASE_CONNECTION);
      databaseConfigureLater = connection == null || connection.isBlank();
      if (databaseConfigureLater) {
        // No connection metadata was supplied. Persist the explicit deferral
        // as "no database yet" rather than fabricating a configured database.
        database = GenerationDatabase.NONE;
      }
    }

    GenerationAuth auth = GenerationAuth.NONE;
    String authAnswer = values.get(AUTH_METHOD);
    if (authAnswer != null && !"NONE".equalsIgnoreCase(authAnswer)) {
      try {
        auth = GenerationAuth.valueOf(authAnswer.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Unknown authentication choice: " + authAnswer);
      }
    }

    List<GenerationIntegration> integrations = parseIntegrations(values.get(INTEGRATIONS));
    if (integrations.contains(GenerationIntegration.STRIPE)
        && integrations.contains(GenerationIntegration.RAZORPAY)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose either Stripe or Razorpay, not both");
    }
    if ((auth == GenerationAuth.JWT || auth == GenerationAuth.OAUTH_GOOGLE)
        && project.backend() == GenerationBackend.NONE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Authentication requires a backend");
    }
    if ("TEMPLATE".equals(engine)
        && (project.backend() != GenerationBackend.JAVA_SPRING_BOOT
            || (project.frontend() != GenerationFrontend.REACT_TYPESCRIPT
                && project.frontend() != GenerationFrontend.NONE))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Template generation supports Spring Boot with React or no frontend");
    }
    boolean aiFeatures = "YES".equalsIgnoreCase(values.get(AI_FEATURES));
    if (aiFeatures && "TEMPLATE".equals(engine)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "AI features require an OpenRouter or custom generation engine");
    }

    return new Resolution(values, database, auth, integrations,
        databaseConfigureLater, List.of());
  }

  /** Question definitions paired with redacted answers, ready for persistence. */
  public static List<StoredAnswer> storedAnswers(
      GenerationQuestionsRequest project, List<GenerationAnswerRequest> answers) {
    List<GenerationQuestion> questions = questions(project);
    Map<String, GenerationAnswerRequest> byId = new LinkedHashMap<>();
    for (GenerationAnswerRequest answer : answers == null ? List.<GenerationAnswerRequest>of() : answers) {
      byId.put(answer.questionId().trim(), answer);
    }
    List<StoredAnswer> stored = new ArrayList<>();
    for (GenerationQuestion question : questions) {
      GenerationAnswerRequest answer = byId.get(question.id());
      if (answer == null) {
        continue;
      }
      boolean secret = question.secret();
      boolean hasValue = !answer.skipped()
          && answer.value() != null && !answer.value().isBlank();
      stored.add(new StoredAnswer(
          question.id(),
          question.prompt(),
          question.type().name(),
          secret || answer.skipped() ? null : answer.value(),
          answer.skipped(),
          question.required(),
          secret,
          secret && hasValue));
    }
    return List.copyOf(stored);
  }

  private static void requireStack(GenerationQuestionsRequest project) {
    if (project.languageRuntime() == null || project.languageRuntime().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Language/runtime is required");
    }
    if (project.backend() == GenerationBackend.NONE
        && project.frontend() == GenerationFrontend.NONE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose at least a frontend or a backend");
    }
    if (project.backend() == GenerationBackend.OTHER
        && (project.backendCustom() == null || project.backendCustom().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Describe the custom backend");
    }
    if (project.frontend() == GenerationFrontend.OTHER
        && (project.frontendCustom() == null || project.frontendCustom().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Describe the custom frontend");
    }
  }

  private static void require(Map<String, String> values, String id, List<String> missing) {
    String value = values.get(id);
    if (value == null || value.isBlank()) {
      missing.add(id);
    }
  }

  static List<GenerationIntegration> parseIntegrations(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    Set<GenerationIntegration> result = new LinkedHashSet<>();
    for (String part : raw.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        result.add(GenerationIntegration.valueOf(part.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Unknown integration: " + part.trim());
      }
    }
    return List.copyOf(result);
  }

  private static GenerationQuestion.QuestionOption option(String value, String label) {
    return new GenerationQuestion.QuestionOption(value, label);
  }

  /** Resolved, non-secret stack derived only from the user's answers. */
  public record Resolution(
      Map<String, String> values,
      GenerationDatabase database,
      GenerationAuth auth,
      List<GenerationIntegration> integrations,
      boolean databaseConfigureLater,
      List<String> missing) {

    public boolean complete() {
      return missing.isEmpty();
    }
  }

  /** One question definition plus its (secret-redacted) answer. */
  public record StoredAnswer(
      String questionId,
      String prompt,
      String type,
      String value,
      boolean skipped,
      boolean required,
      boolean secret,
      boolean configured) {
  }
}
