package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verireview.generation.dto.GenerationAnswerRequest;
import com.verireview.generation.dto.GenerationPlanRequest;
import com.verireview.generation.dto.GenerationQuestion;
import com.verireview.generation.dto.GenerationQuestionsRequest;
import com.verireview.generation.dto.PlanPreviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * The wizard contract is deterministic and no-assumption: every question comes
 * from the backend, missing required answers are rejected by id, skipped
 * answers are explicit, and secrets never enter the persisted JSON.
 */
class GenerationWizardServiceTest {

  private final GenerationWizardService wizard = new GenerationWizardService(new ObjectMapper());

  private static GenerationQuestionsRequest project() {
    return new GenerationQuestionsRequest(
        "task-tracker",
        "A task tracker where teams create projects and assign tasks.",
        "No extra constraints",
        GenerationBackend.JAVA_SPRING_BOOT,
        GenerationFrontend.REACT_TYPESCRIPT,
        "Java 21",
        null,
        null);
  }

  private static GenerationAnswerRequest answer(String id, String value) {
    return new GenerationAnswerRequest(id, value, false);
  }

  private static GenerationAnswerRequest skipped(String id) {
    return new GenerationAnswerRequest(id, "", true);
  }

  private static List<GenerationAnswerRequest> completeTemplateAnswers() {
    return List.of(
        answer(GenerationWizardCatalog.DATABASE_TYPE, "NONE"),
        skipped(GenerationWizardCatalog.DATABASE_CONNECTION),
        answer(GenerationWizardCatalog.AUTH_METHOD, "NONE"),
        answer(GenerationWizardCatalog.AI_FEATURES, "NO"),
        answer(GenerationWizardCatalog.DEPLOYMENT_TARGET, "LOCAL"),
        answer(GenerationWizardCatalog.INTEGRATIONS, "EMAIL"));
  }

  @Test
  void engineIsDerivedFromTheSelectedStack() {
    assertThat(GenerationWizardCatalog.engineFor(
        GenerationBackend.JAVA_SPRING_BOOT, GenerationFrontend.REACT_TYPESCRIPT))
        .isEqualTo("TEMPLATE");
    assertThat(GenerationWizardCatalog.engineFor(
        GenerationBackend.JAVA_SPRING_BOOT, GenerationFrontend.NONE))
        .isEqualTo("TEMPLATE");
    assertThat(GenerationWizardCatalog.engineFor(
        GenerationBackend.NODEJS, GenerationFrontend.REACT_TYPESCRIPT))
        .isEqualTo("AI");
    assertThat(GenerationWizardCatalog.engineFor(
        GenerationBackend.PYTHON_FASTAPI, GenerationFrontend.NONE))
        .isEqualTo("AI");
  }

  @Test
  void questionsCoverTheChoicesTheBackendWouldOtherwiseDefault() {
    List<GenerationQuestion> questions = wizard.questions(project());

    assertThat(questions).extracting(GenerationQuestion::id)
        .contains(
            GenerationWizardCatalog.DATABASE_TYPE,
            GenerationWizardCatalog.DATABASE_CONNECTION,
            GenerationWizardCatalog.AUTH_METHOD,
            GenerationWizardCatalog.AI_FEATURES,
            GenerationWizardCatalog.DEPLOYMENT_TARGET,
            GenerationWizardCatalog.INTEGRATIONS);
    assertThat(questions)
        .filteredOn(question -> question.id().equals(GenerationWizardCatalog.DATABASE_TYPE))
        .singleElement()
        .satisfies(question -> {
          assertThat(question.required()).isTrue();
          assertThat(question.allowSkip()).isTrue();
          assertThat(question.options()).isNotEmpty();
        });
    assertThat(questions)
        .filteredOn(question -> question.id().equals(GenerationWizardCatalog.DATABASE_CONNECTION))
        .singleElement()
        .satisfies(question -> assertThat(question.secret()).isTrue());
  }

  @Test
  void missingRequiredAnswersAreRejectedByIdsAndNeverDefaulted() {
    assertThatThrownBy(() -> wizard.plan(new GenerationPlanRequest(project(), List.of())))
        .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
          assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(error.getReason())
              .contains(GenerationWizardCatalog.DATABASE_TYPE)
              .contains(GenerationWizardCatalog.AUTH_METHOD)
              .contains(GenerationWizardCatalog.DEPLOYMENT_TARGET);
        });
  }

  @Test
  void explicitSkipForDatabaseMeansConfigureLaterWithoutFabricatedConnection() {
    List<GenerationAnswerRequest> answers = List.of(
        skipped(GenerationWizardCatalog.DATABASE_TYPE),
        answer(GenerationWizardCatalog.AUTH_METHOD, "NONE"),
        answer(GenerationWizardCatalog.AI_FEATURES, "NO"),
        answer(GenerationWizardCatalog.ENGINE, "TEMPLATE"),
        answer(GenerationWizardCatalog.DEPLOYMENT_TARGET, "LOCAL"));

    GenerationWizardCatalog.Resolution resolved =
        GenerationWizardCatalog.resolve(project(), answers);

    assertThat(resolved.complete()).isTrue();
    assertThat(resolved.database()).isEqualTo(GenerationDatabase.NONE);
    assertThat(resolved.databaseConfigureLater()).isTrue();

    PlanPreviewResponse plan = wizard.plan(new GenerationPlanRequest(project(), answers));
    assertThat(plan.architecture()).contains("Java 21");
    assertThat(plan.files()).extracting(PlanPreviewResponse.PlanFile::path)
        .contains("docs/database.md");
  }

  @Test
  void rejectsAnswersOutsideTheQuestionOptions() {
    List<GenerationAnswerRequest> answers = List.of(
        answer(GenerationWizardCatalog.DATABASE_TYPE, "NONE"),
        answer(GenerationWizardCatalog.AUTH_METHOD, "NONE"),
        answer(GenerationWizardCatalog.AI_FEATURES, "NO"),
        answer(GenerationWizardCatalog.DATABASE_TYPE, "BOGUS"),
        answer(GenerationWizardCatalog.DEPLOYMENT_TARGET, "LOCAL"));

    assertThatThrownBy(() -> GenerationWizardCatalog.resolve(project(), answers))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unknown choice");
  }

  @Test
  void selectedDatabaseNeedsConnectionDetailsOrAnExplicitAskMeLater() {
    List<GenerationAnswerRequest> unansweredConnection = List.of(
        answer(GenerationWizardCatalog.DATABASE_TYPE, "POSTGRESQL"),
        answer(GenerationWizardCatalog.AUTH_METHOD, "NONE"),
        answer(GenerationWizardCatalog.AI_FEATURES, "NO"),
        answer(GenerationWizardCatalog.DEPLOYMENT_TARGET, "LOCAL"));

    GenerationWizardCatalog.Resolution missing =
        GenerationWizardCatalog.resolve(project(), unansweredConnection);
    assertThat(missing.complete()).isFalse();
    assertThat(missing.missing()).contains(GenerationWizardCatalog.DATABASE_CONNECTION);

    List<GenerationAnswerRequest> explicitlyLater = List.of(
        answer(GenerationWizardCatalog.DATABASE_TYPE, "POSTGRESQL"),
        skipped(GenerationWizardCatalog.DATABASE_CONNECTION),
        answer(GenerationWizardCatalog.AUTH_METHOD, "NONE"),
        answer(GenerationWizardCatalog.AI_FEATURES, "NO"),
        answer(GenerationWizardCatalog.DEPLOYMENT_TARGET, "LOCAL"));

    GenerationWizardCatalog.Resolution later =
        GenerationWizardCatalog.resolve(project(), explicitlyLater);
    assertThat(later.complete()).isTrue();
    assertThat(later.database()).isEqualTo(GenerationDatabase.NONE);
    assertThat(later.databaseConfigureLater()).isTrue();
  }

  @Test
  void secretAnswersAreRedactedFromPersistedJson() {
    List<GenerationAnswerRequest> answers = List.of(
        answer(GenerationWizardCatalog.DATABASE_TYPE, "POSTGRESQL"),
        answer(GenerationWizardCatalog.DATABASE_CONNECTION,
            "postgresql://reviewer:private-password@db.example.test:5432/app"),
        answer(GenerationWizardCatalog.AUTH_METHOD, "NONE"),
        answer(GenerationWizardCatalog.AI_FEATURES, "YES"),
        answer(GenerationWizardCatalog.DEPLOYMENT_TARGET, "DOCKER"));

    String json = wizard.storedAnswersJson(project(), answers);

    assertThat(json)
        .contains("\"questionId\":\"database.connection\"")
        .contains("\"secret\":true")
        .contains("\"configured\":true")
        .doesNotContain("postgresql://reviewer")
        .doesNotContain("private-password")
        .doesNotContain("sk-live-must-not-persist");

    PlanPreviewResponse plan = wizard.plan(new GenerationPlanRequest(project(), answers));
    assertThat(plan.toString())
        .doesNotContain("postgresql://reviewer")
        .doesNotContain("private-password")
        .doesNotContain("sk-live-must-not-persist");
  }

  @Test
  void templatePlanNeedsNoAiKey() {
    PlanPreviewResponse plan =
        wizard.plan(new GenerationPlanRequest(project(), completeTemplateAnswers()));
    assertThat(plan.files()).extracting(PlanPreviewResponse.PlanFile::path)
        .doesNotContain("integrations/ai-llm.md");

  }
}
