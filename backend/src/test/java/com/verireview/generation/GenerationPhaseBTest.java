package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionRepository;
import com.verireview.agent.AgentExecutionStatus;
import com.verireview.agent.AgentType;
import com.verireview.agent.AiServiceException;
import com.verireview.agent.GenerationAiClient;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanRequest;
import com.verireview.agent.dto.AiGenerationPlanResult;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.persistence.AbstractPersistenceTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase B: planning + coding orchestration. The runner drives
 * DRAFT → PLANNING → CODING and parks there — no project, no COMPLETED, no
 * verification claims. The AI service stays mocked at the Java boundary;
 * every assertion below targets backend-controlled behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationPhaseBTest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private ProjectStorage storage;
  @Autowired private GenerationPlanRepository plans;
  @Autowired private GenerationArtifactRepository generationArtifacts;
  @Autowired private AgentExecutionRepository executions;
  @Autowired private GenerationRepository generationRows;
  @MockitoBean private GenerationAiClient generationAiClient;

  private static final String PLAN_FILE = "src/main/java/app/App.java";
  private static final String PLAN_CONTENT = "class App {}";

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
                PLAN_FILE, PLAN_CONTENT, "java")),
            "files ok"));
  }

  @Test
  void planningRequestCarriesRequirementStackAndKey() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    pollUntilSettled(token, id);

    ArgumentCaptor<AiGenerationPlanRequest> captor =
        ArgumentCaptor.forClass(AiGenerationPlanRequest.class);
    verify(generationAiClient).plan(captor.capture());
    AiGenerationPlanRequest request = captor.getValue();
    assertThat(request.requirement()).contains("A minimal todo API");
    assertThat(request.backend()).isEqualTo("PYTHON_FASTAPI");
    assertThat(request.model()).isEqualTo("test/model");
    // Server-to-server credential present…
    assertThat(request.apiKey()).isEqualTo("sk-test-key");
    // …but the contract has no password field at all.
    assertThat(
        Arrays.stream(AiGenerationPlanRequest.class.getRecordComponents())
            .map(c -> c.getName().toLowerCase())
            .collect(Collectors.toSet()))
        .doesNotContain("password", "dbpassword");
  }

  @Test
  void planningResultParsedAndPersisted() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    pollUntilSettled(token, id);

    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan.architecture").value("Single-module app"))
        .andExpect(jsonPath("$.plan.directories[0]").value("src/main/java/app"))
        .andExpect(jsonPath("$.plan.fileCount").value(1))
        .andExpect(jsonPath("$.plan.iteration").value(1));

    GenerationPlan plan = plans
        .findFirstByGenerationIdOrderByIterationDesc(UUID.fromString(id))
        .orElseThrow();
    assertThat(plan.getIteration()).isEqualTo(1);
    assertThat(plan.getFileCount()).isEqualTo(1);
    assertThat(plan.getPlanJson()).contains("Single-module app");
    assertThat(plan.getPlanJson()).doesNotContain("sk-test-key");
    assertThat(plans.findByGenerationIdAndIteration(
        UUID.fromString(id), 1)).isPresent();
  }

  @Test
  void codingResultParsedIntoWorkspaceFiles() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    pollUntilSettled(token, id);

    Path workspaceFile = storage
        .generationWorkspaceDir(UUID.fromString(id), 1)
        .resolve(PLAN_FILE);
    try {
      // Actual file contents on disk — never descriptions of files.
      assertThat(Files.readString(workspaceFile)).isEqualTo(PLAN_CONTENT);
    } finally {
      storage.deleteQuietly(storage.generationWorkspaceDir(UUID.fromString(id), 1));
    }

    // And the metadata row agrees with what is on disk.
    MvcResult result = mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.artifact.fileCount").value(1))
        .andExpect(jsonPath("$.artifact.totalChars").value(PLAN_CONTENT.length()))
        .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("storageRef");
  }

  @Test
  void statesAdvanceDraftToCodingWithoutCompletion() throws Exception {
    String token = access(register());
    CountDownLatch planGate = new CountDownLatch(1);
    when(generationAiClient.plan(any())).thenAnswer(inv -> {
      if (!planGate.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test gate timed out");
      }
      return new AiGenerationPlanResult("g",
          List.of(new AiGenerationPlanResult.PlannedFile(PLAN_FILE, "Entrypoint")),
          "plan ok",
          AiGenerationPlanResult.PlanSections.empty());
    });
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    // While planning is gated, the backend honestly reports PLANNING.
    assertThat(pollForStatus(token, id, "PLANNING").get("status").asText())
        .isEqualTo("PLANNING");
    planGate.countDown();

    JsonNode settled = pollUntilSettled(token, id);
    assertThat(settled.get("status").asText()).isEqualTo("CODING");
    assertThat(settled.get("projectId").isNull()).isTrue();

    // Nothing further happens on its own: no COMPLETED, no VERIFIED jump.
    Thread.sleep(1000);
    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CODING"));
    storage.deleteQuietly(storage.generationWorkspaceDir(UUID.fromString(id), 1));
  }

  @Test
  void agentExecutionsPersistedPerAgent() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    pollUntilSettled(token, id);

    List<AgentExecution> rows =
        executions.findByGenerationIdOrderByCreatedAtAsc(UUID.fromString(id));
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(AgentExecution::getAgentType).toList())
        .containsExactly(AgentType.PLANNING, AgentType.CODING);
    for (AgentExecution row : rows) {
      assertThat(row.getStatus()).isEqualTo(AgentExecutionStatus.COMPLETED);
      assertThat(row.getDurationMs()).isNotNull();
      assertThat(row.getModel()).isEqualTo("test/model");
      assertThat(row.getPromptVersion()).isEqualTo("generation/v1");
      assertThat(row.getInputHash()).matches("[0-9a-f]{64}");
      assertThat(row.getError()).isNull();
      assertThat(row.getOutputRef()).startsWith(
          row.getAgentType() == AgentType.PLANNING ? "plan:" : "artifact:");
    }
    storage.deleteQuietly(storage.generationWorkspaceDir(UUID.fromString(id), 1));
  }

  @Test
  void generationOwnershipEnforced() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    String id = createGeneration(ownerToken, validBody("gen-" + UUID.randomUUID()));

    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    pollUntilSettled(ownerToken, id);
    storage.deleteQuietly(storage.generationWorkspaceDir(UUID.fromString(id), 1));
  }

  @Test
  void malformedPlanResponseFailsHonestly() throws Exception {
    when(generationAiClient.plan(any())).thenThrow(
        new AiServiceException(AiServiceException.Kind.MALFORMED, "plan output is not JSON"));
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("error").asText()).contains("Planning failed");
    assertThat(terminal.get("projectId").isNull()).isTrue();
  }

  @Test
  void emptyFileContentFailsHonestly() throws Exception {
    when(generationAiClient.files(any())).thenReturn(
        new AiGenerationFilesResult("g",
            List.of(new AiGenerationFilesResult.GeneratedFile(PLAN_FILE, "", "java")),
            "empty"));
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("error").asText()).contains("empty");
    assertThat(terminal.get("projectId").isNull()).isTrue();
  }

  @Test
  void traversalPlanPathFailsHonestly() throws Exception {
    when(generationAiClient.plan(any())).thenAnswer(inv ->
        new AiGenerationPlanResult("g",
            List.of(new AiGenerationPlanResult.PlannedFile("../evil.sh", "Escape")),
            "evil plan",
            AiGenerationPlanResult.PlanSections.empty()));
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("error").asText()).contains("illegal path");
  }

  @Test
  void absoluteFilesPathFailsHonestly() throws Exception {
    when(generationAiClient.files(any())).thenReturn(
        new AiGenerationFilesResult("g",
            List.of(new AiGenerationFilesResult.GeneratedFile("/etc/passwd", "x", "")),
            "evil files"));
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("error").asText()).contains("illegal path");
  }

  @Test
  void iterationAssociatedAcrossRecords() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    pollUntilSettled(token, id);
    UUID generationId = UUID.fromString(id);

    assertThat(generationRows.findById(generationId).orElseThrow().getIteration()).isEqualTo(1);
    assertThat(executions.findByGenerationIdOrderByCreatedAtAsc(generationId))
        .isNotEmpty()
        .allSatisfy(row -> assertThat(row.getGeneration().getId()).isEqualTo(generationId));
    assertThat(artifactsCount(generationId)).isEqualTo(1);
    storage.deleteQuietly(storage.generationWorkspaceDir(generationId, 1));
  }

  @Test
  void maxIterationsStopsRunHonestly() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));
    UUID generationId = UUID.fromString(id);
    Generation managed = generationRows.findById(generationId).orElseThrow();
    managed.setMaxIterations(0);
    generationRows.saveAndFlush(managed);

    MvcResult started = mockMvc.perform(post("/api/v1/generations/" + id + "/start")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"apiKey\":\"sk-test-key\"}"))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(started.getResponse().getContentAsString()).doesNotContain("sk-test-key");

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("error").asText()).contains("Maximum fix-loop iterations reached");
    assertThat(terminal.get("projectId").isNull()).isTrue();
  }

  private long artifactsCount(UUID generationId) {
    return generationArtifacts.findByGenerationIdOrderByCreatedAtAsc(generationId).size();
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
      if (status.equals("CODING") || status.equals("COMPLETED") || status.equals("FAILED")) {
        return root;
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Generation did not finish in time: " + status);
      }
      Thread.sleep(250);
    }
  }

  private JsonNode pollForStatus(String token, String id, String want) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (true) {
      MvcResult result = mockMvc.perform(get("/api/v1/generations/" + id)
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode root = objects.readTree(result.getResponse().getContentAsString());
      if (root.get("status").asText().equals(want)) {
        return root;
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Generation never reached " + want);
      }
      Thread.sleep(250);
    }
  }

  private String register() throws Exception {
    String email = "phase-b-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"PhaseB\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
