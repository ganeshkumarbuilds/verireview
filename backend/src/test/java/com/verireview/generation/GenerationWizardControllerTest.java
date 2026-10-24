package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.persistence.AbstractPersistenceTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP contract for the Generate wizard. The endpoints are authenticated,
 * validate steps 1–2, never create a job before approval, and never persist
 * a secret answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationWizardControllerTest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private GenerationRepository generations;

  private static final String PROJECT = """
      {"name":"task-tracker","requirement":"A task tracker where teams create projects and assign tasks.",
       "backend":"JAVA_SPRING_BOOT","frontend":"REACT_TYPESCRIPT","languageRuntime":"Java 21"}
      """;

  private static final String COMPLETE_ANSWERS = """
      [{"questionId":"database.type","value":"NONE","skipped":false},
       {"questionId":"database.connection","value":"","skipped":true},
       {"questionId":"auth.method","value":"NONE","skipped":false},
       {"questionId":"ai.features","value":"NO","skipped":false},
       {"questionId":"generation.engine","value":"OPENROUTER","skipped":false},
       {"questionId":"generation.model","value":"openai/test-model","skipped":false},
       {"questionId":"generation.apiKey","value":"sk-must-never-be-stored","skipped":false},
       {"questionId":"deployment.target","value":"LOCAL","skipped":false}]
      """;

  private String token() throws Exception {
    String email = "wizard-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\","
                + "\"displayName\":\"Wizard\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  @Test
  void questionsRequireAuthAndReturnTheBackendCatalog() throws Exception {
    mockMvc.perform(post("/api/v1/generate/questions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(PROJECT))
        .andExpect(status().isUnauthorized());

    String token = token();
    mockMvc.perform(post("/api/v1/generate/questions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(PROJECT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == 'database.type')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'auth.method')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'generation.engine')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'deployment.target')]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'generation.apiKey')].secret")
            .value(org.hamcrest.Matchers.contains(true)));
  }

  @Test
  void questionsRejectMissingStackAndShortDescription() throws Exception {
    String token = token();
    mockMvc.perform(post("/api/v1/generate/questions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"too short\","
                + "\"backend\":\"JAVA_SPRING_BOOT\",\"frontend\":\"NONE\"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/v1/generate/questions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"A sufficiently long task description\","
                + "\"backend\":\"NONE\",\"frontend\":\"NONE\",\"languageRuntime\":\"none\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void planRejectsUnansweredRequiredQuestionsBeforeAnyJobExists() throws Exception {
    String token = token();
    mockMvc.perform(post("/api/v1/generate/plan")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project\":" + PROJECT + ",\"answers\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString(GenerationWizardCatalog.DATABASE_TYPE)))
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString(GenerationWizardCatalog.ENGINE)));

    mockMvc.perform(get("/api/v1/generations")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void planUsesExplicitAnswersAndDoesNotPersistAnything() throws Exception {
    String token = token();
    mockMvc.perform(post("/api/v1/generate/plan")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"project\":" + PROJECT + ",\"answers\":" + COMPLETE_ANSWERS + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.architecture").value(
            org.hamcrest.Matchers.containsString("Language/runtime: Java 21.")))
        .andExpect(jsonPath("$.notes").value(
            org.hamcrest.Matchers.containsString("nothing was assumed")))
        .andExpect(jsonPath("$.files[?(@.path == 'docs/database.md')]").doesNotExist())
        .andExpect(jsonPath("$.files[?(@.path == 'integrations/ai-llm.md')]").doesNotExist());
  }

  @Test
  void createRejectsAiConfigThatDisagreesWithTheApprovedAnswers() throws Exception {
    String token = token();
    String answers = COMPLETE_ANSWERS.replace("\"value\":\"OPENROUTER\"", "\"value\":\"TEMPLATE\"");
    String body = "{\"name\":\"mismatch-" + UUID.randomUUID() + "\","
        + "\"requirement\":\"A task tracker where teams create projects and assign tasks.\","
        + "\"backend\":\"JAVA_SPRING_BOOT\",\"frontend\":\"REACT_TYPESCRIPT\","
        + "\"languageRuntime\":\"Java 21\",\"database\":\"NONE\",\"auth\":\"NONE\","
        + "\"integrations\":[],\"answers\":" + answers + ","
        + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"apiKey\":\"sk-test\","
        + "\"model\":\"openai/test-model\"},\"draft\":true}";
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString("does not match")));
  }

  @Test
  void createPersistsRedactedAnswersAndExplicitConfigureLater() throws Exception {
    String token = token();
    String body = """
        {"name":"wizard-answer-""" + UUID.randomUUID() + """
        ","requirement":"A task tracker where teams create projects and assign tasks.",
         "backend":"JAVA_SPRING_BOOT","frontend":"REACT_TYPESCRIPT","languageRuntime":"Java 21",
         "database":"NONE","auth":"NONE","integrations":[],
         "answers":""" + COMPLETE_ANSWERS + """
        ,"aiConfig":{"provider":"OPENROUTER","apiKey":"sk-must-never-be-stored",
          "model":"openai/test-model"},"draft":true}
        """;
    MvcResult created = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.databaseConfigureLater").value(false))
        .andExpect(jsonPath("$.languageRuntime").value("Java 21"))
        .andReturn();

    String response = created.getResponse().getContentAsString();
    assertThat(response).doesNotContain("sk-must-never-be-stored");
    JsonNode root = objects.readTree(response);
    JsonNode apiKeyAnswer = null;
    for (JsonNode answer : root.get("answers")) {
      if (GenerationWizardCatalog.API_KEY.equals(answer.get("questionId").asText())) {
        apiKeyAnswer = answer;
      }
    }
    assertThat(apiKeyAnswer).isNotNull();
    assertThat(apiKeyAnswer.get("value").isNull()).isTrue();
    assertThat(apiKeyAnswer.get("secret").asBoolean()).isTrue();
    assertThat(apiKeyAnswer.get("configured").asBoolean()).isTrue();

    String id = root.get("id").asText();
    String persisted = generations.findById(UUID.fromString(id)).orElseThrow().getAnswersJson();
    assertThat(persisted)
        .contains(GenerationWizardCatalog.DATABASE_TYPE)
        .contains(GenerationWizardCatalog.DEPLOYMENT_TARGET)
        .contains("\"configured\":true")
        .doesNotContain("sk-must-never-be-stored");
  }
}
