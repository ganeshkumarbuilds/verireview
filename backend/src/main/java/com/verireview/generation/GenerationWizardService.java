package com.verireview.generation;

import com.verireview.generation.dto.GenerationAnswerRequest;
import com.verireview.generation.dto.GenerationPlanRequest;
import com.verireview.generation.dto.GenerationQuestion;
import com.verireview.generation.dto.GenerationQuestionsRequest;
import com.verireview.generation.dto.PlanPreviewResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Backend authority for the Generate wizard's clarifying questions and plan.
 *
 * <p>The service never calls an LLM and never persists here: it derives the
 * questions deterministically, rejects unanswered required questions, and
 * builds the plan from the explicit answers only. Secret answers are validated
 * but never echoed. {@link #storedAnswersJson} is the single place that turns
 * answers into the persisted, secret-filtered JSON.
 */
@Service
public class GenerationWizardService {

  private final ObjectMapper objects;

  public GenerationWizardService(ObjectMapper objects) {
    this.objects = objects;
  }

  public List<GenerationQuestion> questions(GenerationQuestionsRequest project) {
    return GenerationWizardCatalog.questions(project);
  }

  public PlanPreviewResponse plan(GenerationPlanRequest request) {
    GenerationQuestionsRequest project = request.project();
    GenerationWizardCatalog.Resolution resolved =
        GenerationWizardCatalog.resolve(project, request.answers());
    if (!resolved.complete()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Answer these questions before planning: " + String.join(", ", resolved.missing()));
    }
    Map<String, String> values = new LinkedHashMap<>(resolved.values());
    values.put("database.configureLater", String.valueOf(resolved.databaseConfigureLater()));
    values.remove(GenerationWizardCatalog.DATABASE_CONNECTION);
    return GenerationScaffoldPreview.buildWizard(
        project.backend(),
        project.frontend(),
        resolved.database(),
        resolved.auth(),
        resolved.integrations(),
        project.languageRuntime(),
        project.backendCustom(),
        project.frontendCustom(),
        values);
  }

  /**
   * Persisted answers for a create request: question metadata plus values,
   * with secret values replaced by a configured flag. When answers are present
   * they are validated against the stack first, so a create cannot smuggle an
   * answer the wizard never asked.
   */
  public String storedAnswersJson(
      GenerationQuestionsRequest project, List<GenerationAnswerRequest> answers) {
    if (answers == null || answers.isEmpty()) {
      return "[]";
    }
    GenerationWizardCatalog.resolve(project, answers);
    List<GenerationWizardCatalog.StoredAnswer> stored =
        GenerationWizardCatalog.storedAnswers(project, answers);
    try {
      return objects.writeValueAsString(stored);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode generation answers", e);
    }
  }

  /** Resolves a create request's answers into the stack the backend persists. */
  public GenerationWizardCatalog.Resolution resolve(
      GenerationQuestionsRequest project, List<GenerationAnswerRequest> answers) {
    return GenerationWizardCatalog.resolve(project, answers);
  }
}
