package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionRepository;
import com.verireview.agent.AgentExecutionStatus;
import com.verireview.agent.AgentType;
import com.verireview.agent.GenerationAiClient;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanResult;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase A: generation domain — revisions, state machine, iteration budget,
 * artifact metadata, and generation links. No agent logic is exercised here;
 * the AI boundary stays mocked and the runner is only drained, never driven.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationPhaseATest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private GenerationService generations;
  @Autowired private GenerationRepository generationRows;
  @Autowired private UserRepository users;
  @Autowired private ProjectRepository projects;
  @Autowired private AgentExecutionRepository agentExecutions;
  @MockitoBean private GenerationAiClient generationAiClient;

  private static final String PLAN_FILE = "src/main/java/app/App.java";

  @BeforeEach
  void stubGenerationAi() {
    when(generationAiClient.plan(any())).thenAnswer(inv ->
        new AiGenerationPlanResult("g",
            List.of(new AiGenerationPlanResult.PlannedFile(PLAN_FILE, "Entrypoint")),
            "plan ok",
            new AiGenerationPlanResult.PlanSections(
                "Single-module app", List.of(), List.of("src/main/java/app"), "", List.of())));
    when(generationAiClient.files(any())).thenAnswer(inv ->
        new AiGenerationFilesResult("g",
            List.of(new AiGenerationFilesResult.GeneratedFile(
                PLAN_FILE, "class App {}", "java")),
            "files ok"));
  }

  @Test
  void createStartsRevisionChainAtOne() throws Exception {
    String token = access(register());
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody("gen-" + UUID.randomUUID())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.revisionNumber").value(1))
        .andExpect(jsonPath("$.revisionCount").value(1))
        .andExpect(jsonPath("$.iteration").value(0))
        .andExpect(jsonPath("$.maxIterations").value(Generation.MAX_ITERATIONS))
        .andExpect(jsonPath("$.artifact").doesNotExist())
        .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("storageRef");
    assertThat(body).doesNotContain("password");
    assertThat(body).doesNotContain("apiKey");
  }

  @Test
  void appendRevisionInDraftVersionsTheTask() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));

    MvcResult second = mockMvc.perform(post("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"Also add role-based authentication.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.revisionNumber").value(2))
        .andExpect(jsonPath("$.requirement").value("Also add role-based authentication."))
        .andReturn();
    assertThat(second.getResponse().getContentAsString()).doesNotContain("storageRef");

    MvcResult listed = mockMvc.perform(get("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode revisions = objects.readTree(listed.getResponse().getContentAsString());
    assertThat(revisions.size()).isEqualTo(2);
    assertThat(revisions.get(0).get("revisionNumber").asInt()).isEqualTo(1);
    assertThat(revisions.get(1).get("revisionNumber").asInt()).isEqualTo(2);
    assertThat(revisions.get(1).get("requirement").asText())
        .isEqualTo("Also add role-based authentication.");

    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirement").value("Also add role-based authentication."))
        .andExpect(jsonPath("$.revisionNumber").value(2))
        .andExpect(jsonPath("$.revisionCount").value(2));
  }

  @Test
  void appendRevisionValidation() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"   \"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/v1/generations/" + id + "/revisions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"x\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void appendRevisionConflictWhenRunning() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    // QUEUED or beyond — either way the job has left DRAFT, so editing conflicts.
    mockMvc.perform(post("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"Too late to change.\"}"))
        .andExpect(status().isConflict());

    // Hermetic: drain the async worker before the next test resets mocks.
    pollUntilSettled(token, id);
  }

  @Test
  void appendRevisionOwnership() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    String id = createGeneration(ownerToken, draftBody("gen-" + UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"Hijacked task.\"}"))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/v1/generations/" + id + "/revisions")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void illegalTransitionsRejected() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));
    UUID generationId = UUID.fromString(id);

    // DRAFT can never jump straight to execution states.
    assertThatThrownBy(() -> generations.markStatus(generationId, GenerationStatus.COMPLETED, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Illegal generation transition from DRAFT to COMPLETED");

    generations.markStatus(generationId, GenerationStatus.CANCELLED, "no longer needed");
    // Terminal states have no outgoing edges.
    assertThatThrownBy(() -> generations.markStatus(generationId, GenerationStatus.PLANNING, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Illegal generation transition from CANCELLED to PLANNING");
  }

  @Test
  void iterationGuardEnforced() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));
    UUID generationId = UUID.fromString(id);

    for (int expected = 1; expected <= Generation.MAX_ITERATIONS; expected++) {
      assertThat(generations.nextIteration(generationId)).isEqualTo(expected);
    }
    assertThatThrownBy(() -> generations.nextIteration(generationId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Maximum fix-loop iterations reached");

    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.iteration").value(Generation.MAX_ITERATIONS))
        .andExpect(jsonPath("$.maxIterations").value(Generation.MAX_ITERATIONS));
  }

  @Test
  void artifactRecordedWithoutStorageRef() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));
    UUID generationId = UUID.fromString(id);

    generations.recordArtifact(generationId, 1, 3, 120L,
        "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        "/srv/verireview/artifacts/" + id + "/iter-1");

    MvcResult result = mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.artifact.iteration").value(1))
        .andExpect(jsonPath("$.artifact.fileCount").value(3))
        .andExpect(jsonPath("$.artifact.sha256").value(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
        .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("storageRef");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("/srv/verireview");
  }

  @Test
  void agentExecutionLinksToGeneration() throws Exception {
    String token = access(register());
    User owner = users.findById(ownerId(token)).orElseThrow();
    Project project = projects.save(
        new Project(owner, "phase-a-" + UUID.randomUUID(), ProjectSourceType.PASTE));

    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));
    Generation generation = generationRows.findById(UUID.fromString(id)).orElseThrow();

    AgentExecution execution = new AgentExecution(
        project, AgentType.PLANNING, "openrouter/test", "generation/v1", "abc123hash");
    execution.setGeneration(generation);
    AgentExecution saved = agentExecutions.saveAndFlush(execution);

    assertThat(saved.getId()).isNotNull();
    assertThat(agentExecutions.findById(saved.getId()).orElseThrow().getGeneration().getId())
        .isEqualTo(generation.getId());
  }

  private String draftBody(String name) {
    return "{\"name\":\"" + name + "\","
        + "\"requirement\":\"Create a task management application.\","
        + "\"description\":\"demo\","
        + "\"backend\":\"PYTHON_FASTAPI\",\"frontend\":\"NONE\",\"database\":\"NONE\","
        + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"model\":\"test/model\"},\"draft\":true}";
  }

  private String validBody(String name) {
    return "{\"name\":\"" + name + "\","
        + "\"requirement\":\"A minimal todo API with tests\","
        + "\"description\":\"demo\","
        + "\"backend\":\"PYTHON_FASTAPI\",\"frontend\":\"NONE\",\"database\":\"POSTGRESQL\","
        + "\"databaseConfig\":{\"host\":\"localhost\",\"port\":5432,\"name\":\"todos\","
        + "\"username\":\"app\",\"password\":\"s3cr3t-db-pw\"},"
        + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"apiKey\":\"sk-test-key\",\"model\":\"test/model\"}}";
  }

  private String createGeneration(String token, String body) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private JsonNode pollUntilSettled(String token, String id) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (true) {
      MvcResult result = mockMvc.perform(get("/api/v1/generations/" + id)
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode root = objects.readTree(result.getResponse().getContentAsString());
      String status = root.get("status").asText();
      // Phase B parks successful runs at CODING (build/verify arrive later).
      if (status.equals("CODING") || status.equals("COMPLETED") || status.equals("FAILED")) {
        return root;
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Generation did not finish in time: " + status);
      }
      Thread.sleep(250);
    }
  }

  private String register() throws Exception {
    String email = "phase-a-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"PhaseA\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }

  private UUID ownerId(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    return UUID.fromString(
        objects.readTree(me.getResponse().getContentAsString()).get("id").asText());
  }
}
