package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.agent.GenerationAiClient;
import com.verireview.agent.ReviewAiClient;
import com.verireview.agent.VerifiedAgentClient;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.verification.VerificationVerdict;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanResult;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.persistence.AbstractPersistenceTest;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generate workflow: wizard validation, secret handling, and async states
 * through CODING. Phase B parks successful runs at CODING with validated
 * files in an isolated workspace — no project materialization, no COMPLETED,
 * no verification claims. Secrets must never appear in responses; the AI
 * service is mocked at the Java boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationTest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private ProjectStorage storage;
  @MockitoBean private GenerationAiClient generationAiClient;
  @MockitoBean private VerifiedAgentClient verifiedAgentClient;
  @MockitoBean private ReviewAiClient reviewAiClient;

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
    VerifiedAgentClient.VerifiedAgentResult verified = mock(VerifiedAgentClient.VerifiedAgentResult.class);
    when(verified.testsTotal()).thenReturn(1);
    when(verified.testsPassed()).thenReturn(1);
    when(verified.testsFailed()).thenReturn(0);
    when(verified.testsSkipped()).thenReturn(0);
    when(verified.verdictEnum()).thenReturn(VerificationVerdict.VERIFIED);
    when(verified.logRef()).thenReturn("test-verification");
    when(verified.reason()).thenReturn("verified");
    when(verifiedAgentClient.evaluate(any(), anyInt(), any(), any())).thenReturn(verified);

    AiReviewResult review = mock(AiReviewResult.class);
    when(review.findings()).thenReturn(List.of());
    when(review.promptVersion()).thenReturn("test-review");
    when(reviewAiClient.review(any())).thenReturn(review);
  }

  @Test
  void createQueuesAndMasksSecrets() throws Exception {
    String token = access(register());
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBody("gen-" + UUID.randomUUID())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.databaseConfig.passwordConfigured").value(true))
        .andExpect(jsonPath("$.aiConfig.keyConfigured").value(true))
        .andExpect(jsonPath("$.aiConfig.model").value("test/model"))
        .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("s3cr3t-db-pw");
    assertThat(body).doesNotContain("sk-test-key");
    JsonNode root = objects.readTree(body);
    assertThat(root.get("databaseConfig").has("password")).isFalse();
    assertThat(root.get("aiConfig").has("apiKey")).isFalse();
    assertThat(root.get("aiConfig").has("key")).isFalse();
    // Hermetic: drain the async worker before the next test resets mocks.
    pollUntilSettled(token, root.get("id").asText());
  }

  @Test
  void validationRejectsIncompleteWizard() throws Exception {
    String token = access(register());

    // Database selected but no databaseConfig.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"req\",\"backend\":\"PYTHON_FASTAPI\","
                + "\"frontend\":\"NONE\",\"database\":\"POSTGRESQL\","
                + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"apiKey\":\"k\",\"model\":\"m\"}}"))
        .andExpect(status().isBadRequest());

    // databaseConfig supplied while NONE selected.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"req\",\"backend\":\"PYTHON_FASTAPI\","
                + "\"frontend\":\"NONE\",\"database\":\"NONE\","
                + "\"databaseConfig\":{\"host\":\"h\",\"port\":5432,\"name\":\"d\",\"username\":\"u\",\"password\":\"p\"},"
                + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"apiKey\":\"k\",\"model\":\"m\"}}"))
        .andExpect(status().isBadRequest());

    // Custom provider without baseUrl.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"req\",\"backend\":\"PYTHON_FASTAPI\","
                + "\"frontend\":\"NONE\",\"database\":\"NONE\","
                + "\"aiConfig\":{\"provider\":\"CUSTOM\",\"apiKey\":\"k\",\"model\":\"m\"}}"))
        .andExpect(status().isBadRequest());

    // Missing API key.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"req\",\"backend\":\"PYTHON_FASTAPI\","
                + "\"frontend\":\"NONE\",\"database\":\"NONE\","
                + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"model\":\"m\"}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void runCompletesVerifiedReviewPipelineWithWorkspaceFiles() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    // Phase B ends at CODING: files validated into an isolated workspace,
    // metadata persisted — no project, no COMPLETED, no verification claims.
    JsonNode settled = pollUntilSettled(token, id);
    assertThat(settled.get("status").asText()).isEqualTo("REVIEWED");
    assertThat(settled.get("projectId").isNull()).isTrue();
    assertThat(settled.get("iteration").asInt()).isEqualTo(1);
    assertThat(settled.get("artifact").get("fileCount").asInt()).isEqualTo(1);
    assertThat(settled.get("plan").get("fileCount").asInt()).isEqualTo(1);
    assertThat(settled.get("plan").get("architecture").asText()).isEqualTo("Single-module app");

    // Actual file contents land on disk in the isolated workspace.
    Path workspaceFile = storage
        .generationWorkspaceDir(UUID.fromString(id), 1)
        .resolve(PLAN_FILE);
    try {
      assertThat(Files.readString(workspaceFile)).isEqualTo("class App {}");
    } finally {
      storage.deleteQuietly(
          storage.generationWorkspaceDir(UUID.fromString(id), 1));
    }
  }

  @Test
  void embeddedSecretsFailTheRun() throws Exception {
    when(generationAiClient.files(any())).thenReturn(
        new AiGenerationFilesResult("g",
            List.of(new AiGenerationFilesResult.GeneratedFile(
                PLAN_FILE, "password=s3cr3t-db-pw", "java")),
            "leak"));
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("error").asText()).contains("environment variables");
    assertThat(terminal.get("projectId").isNull()).isTrue();
  }

  @Test
  void aiUnavailableFailsTheRunWithoutProject() throws Exception {
    when(generationAiClient.plan(any())).thenThrow(
        new com.verireview.agent.AiServiceException(
            com.verireview.agent.AiServiceException.Kind.UNAVAILABLE, "AI down"));
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("FAILED");
    assertThat(terminal.get("projectId").isNull()).isTrue();
  }

  @Test
  void ownershipAndAuthAreEnforced() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    String id = createGeneration(ownerToken, validBody("gen-" + UUID.randomUUID()));

    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/generations/" + id))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/v1/generations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBody("gen-" + UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
    // Hermetic: drain the async worker before the next test resets mocks.
    pollUntilSettled(ownerToken, id);
  }

  @Test
  void saveDraftPersistsWithoutDispatchOrSecrets() throws Exception {
    String token = access(register());
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody("gen-" + UUID.randomUUID())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.databaseConfig.passwordConfigured").value(false))
        .andExpect(jsonPath("$.aiConfig.keyConfigured").value(false))
        .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("\"password\":");
    assertThat(body).doesNotContain("\"apiKey\":");
    String id = objects.readTree(body).get("id").asText();

    // Nothing was dispatched: still DRAFT, no project linked. No worker to drain.
    MvcResult fetched = mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andReturn();
    assertThat(objects.readTree(fetched.getResponse().getContentAsString())
        .get("projectId").isNull()).isTrue();
  }

  @Test
  void draftStillRequiresCoherentConfiguration() throws Exception {
    String token = access(register());
    // Database selected but no databaseConfig, even as a draft.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"requirement\":\"req\",\"backend\":\"PYTHON_FASTAPI\","
                + "\"frontend\":\"NONE\",\"database\":\"POSTGRESQL\","
                + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"model\":\"m\"},\"draft\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void startDraftMovesThroughReady() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));

    MvcResult started = mockMvc.perform(post("/api/v1/generations/" + id + "/start")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"s3cr3t-db-pw\",\"apiKey\":\"sk-test-key\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andReturn();
    assertThat(started.getResponse().getContentAsString()).doesNotContain("sk-test-key");

    JsonNode terminal = pollUntilSettled(token, id);
    assertThat(terminal.get("status").asText()).isEqualTo("REVIEWED");
    assertThat(terminal.get("projectId").isNull()).isTrue();
  }

  @Test
  void startRequiresSecretsAndDraftState() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));

    // Missing API key.
    mockMvc.perform(post("/api/v1/generations/" + id + "/start")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"s3cr3t-db-pw\"}"))
        .andExpect(status().isBadRequest());

    // Still a draft after the rejected start.
    mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"));

    // Starting a dispatched (non-draft) job conflicts.
    String runningId = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    mockMvc.perform(post("/api/v1/generations/" + runningId + "/start")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"s3cr3t-db-pw\",\"apiKey\":\"sk-test-key\"}"))
        .andExpect(status().isConflict());
    pollUntilSettled(token, runningId);
  }

  @Test
  void updateDraftTaskAndConfig() throws Exception {
    String token = access(register());
    String id = createGeneration(token, draftBody("gen-" + UUID.randomUUID()));

    mockMvc.perform(patch("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"Updated task: a blog API with comments\","
                + "\"backend\":\"PYTHON_FASTAPI\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirement").value("Updated task: a blog API with comments"))
        .andExpect(jsonPath("$.status").value("DRAFT"));

    // Blank requirement is rejected; the draft is unchanged.
    mockMvc.perform(patch("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateRejectsDispatchedJobs() throws Exception {
    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    pollUntilSettled(token, id);

    mockMvc.perform(patch("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"too late\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void bulkFixRequestsApproveAllOpenFindingsWithoutMutatingCode() throws Exception {
    AiReviewResult withFindings = mock(AiReviewResult.class);
    when(withFindings.findings()).thenReturn(List.of(
        new AiProposedFinding("BUG", "HIGH", "Null guard missing", "Add a null guard",
            "src/main/java/app/App.java", 1, 2, null, "AI", "add guard", 0.9),
        new AiProposedFinding("CODE_QUALITY", "MEDIUM", "Long method", "Split the method",
            "src/main/java/app/App.java", 1, 5, null, "AI", "split method", 0.7)));
    when(withFindings.promptVersion()).thenReturn("test-review");
    when(reviewAiClient.review(any())).thenReturn(withFindings);

    String token = access(register());
    String id = createGeneration(token, validBody("gen-" + UUID.randomUUID()));
    JsonNode settled = pollUntilSettled(token, id);
    assertThat(settled.get("status").asText()).isEqualTo("REVIEWED");
    assertThat(settled.get("workflowStats").get("openFindings").asInt()).isEqualTo(2);

    MvcResult bulk = mockMvc.perform(post("/api/v1/generations/" + id + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scopeNote\":\"fix all open findings\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.created.length()").value(2))
        .andExpect(jsonPath("$.skippedOpen").value(0))
        .andReturn();
    assertThat(bulk.getResponse().getContentAsString()).doesNotContain("sk-test-key");

    // Idempotent: findings with open requests are skipped, never duplicated.
    mockMvc.perform(post("/api/v1/generations/" + id + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.created.length()").value(0))
        .andExpect(jsonPath("$.skippedOpen").value(2));
  }

  @Test
  void bulkFixRequestsRequireReviewedStatusAndOwnership() throws Exception {
    String ownerToken = access(register());
    String draftId = createGeneration(ownerToken, draftBody("gen-" + UUID.randomUUID()));

    // Drafts are not fixable: the fix loop starts from REVIEWED.
    mockMvc.perform(post("/api/v1/generations/" + draftId + "/fix-requests")
            .header("Authorization", "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict());

    // Foreign owners see 404, never 403.
    String otherToken = access(register());
    mockMvc.perform(post("/api/v1/generations/" + draftId + "/fix-requests")
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void projectGenerationLookupIsOwnerScoped() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());

    // A project with no generation answers 404.
    MvcResult shell = mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"plain-" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    String plainId = objects.readTree(shell.getResponse().getContentAsString()).get("id").asText();
    mockMvc.perform(get("/api/v1/projects/" + plainId + "/generation")
            .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isNotFound());

    // Foreign user cannot reach another owner's project generation either.
    mockMvc.perform(get("/api/v1/projects/" + plainId + "/generation")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    // Unknown projects answer 404 (never 403 — no enumeration oracle).
    mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/generation")
            .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void draftOwnershipIsEnforced() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    String id = createGeneration(ownerToken, draftBody("gen-" + UUID.randomUUID()));

    mockMvc.perform(patch("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"hijack\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/v1/generations/" + id + "/start")
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"x\",\"apiKey\":\"y\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(patch("/api/v1/generations/" + id))
        .andExpect(status().isUnauthorized());
  }

  private String draftBody(String name) {
    return "{\"name\":\"" + name + "\","
        + "\"requirement\":\"A minimal todo API with tests\","
        + "\"description\":\"demo\","
        + "\"backend\":\"PYTHON_FASTAPI\",\"frontend\":\"NONE\",\"database\":\"POSTGRESQL\","
        + "\"databaseConfig\":{\"host\":\"localhost\",\"port\":5432,\"name\":\"todos\","
        + "\"username\":\"app\"},"
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
      // Successful runs settle after build, verification, and review.
      if (status.equals("REVIEWED") || status.equals("COMPLETED") || status.equals("FAILED")) {
        return root;
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Generation did not finish in time: " + status);
      }
      Thread.sleep(250);
    }
  }

  private String register() throws Exception {
    String email = "gen-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Gen\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
