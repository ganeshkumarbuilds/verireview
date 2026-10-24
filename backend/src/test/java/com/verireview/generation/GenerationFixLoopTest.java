package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionRepository;
import com.verireview.agent.AgentType;
import com.verireview.agent.GenerationAiClient;
import com.verireview.agent.ReviewAiClient;
import com.verireview.agent.VerifiedAgentClient;
import com.verireview.agent.dto.AiGenerationFilesRequest;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanResult;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.execution.SandboxRunner;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.verification.VerificationVerdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class GenerationFixLoopTest extends AbstractPersistenceTest {

  private static final String APP = "src/main/java/app/App.java";
  private static final String HELPER = "src/main/java/app/Helper.java";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private ProjectStorage storage;
  @Autowired private GenerationRepository generations;
  @Autowired private GenerationFileVersionRepository fileVersions;
  @Autowired private AgentExecutionRepository executions;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private GenerationAiClient generationAiClient;
  @MockitoBean private VerifiedAgentClient verifiedAgentClient;
  @MockitoBean private ReviewAiClient reviewAiClient;
  @MockitoSpyBean private SandboxRunner sandboxRunner;

  @BeforeEach
  void stubAgents() {
    when(sandboxRunner.executeBuild(any(Path.class), any(String.class)))
        .thenReturn(new SandboxRunner.ExecutionResult(0, "BUILD SUCCESS", "", 10L, false));
    when(generationAiClient.plan(any())).thenReturn(new AiGenerationPlanResult(
        "plan",
        List.of(
            new AiGenerationPlanResult.PlannedFile(APP, "Application"),
            new AiGenerationPlanResult.PlannedFile(HELPER, "Application dependent")),
        "plan ok",
        new AiGenerationPlanResult.PlanSections(
            "Two files", List.of(), List.of("src/main/java/app"), "", List.of())));
    when(generationAiClient.files(any(AiGenerationFilesRequest.class))).thenAnswer(invocation -> {
      AiGenerationFilesRequest request = invocation.getArgument(0);
      boolean fixing = request.fixRound() != null && request.fixRound() > 1;
      return new AiGenerationFilesResult("files", List.of(
          new AiGenerationFilesResult.GeneratedFile(
              APP, fixing ? "class App { int fixed; }" : "class App {}", "java"),
          new AiGenerationFilesResult.GeneratedFile(
              HELPER, fixing ? "class Helper { App app; int fixed; }" : "class Helper { App app; }",
              "java")), "files ok");
    });
    VerifiedAgentClient.VerifiedAgentResult verified =
        org.mockito.Mockito.mock(VerifiedAgentClient.VerifiedAgentResult.class);
    when(verified.testsTotal()).thenReturn(1);
    when(verified.testsPassed()).thenReturn(1);
    when(verified.testsFailed()).thenReturn(0);
    when(verified.testsSkipped()).thenReturn(0);
    when(verified.verdictEnum()).thenReturn(VerificationVerdict.VERIFIED);
    when(verified.logRef()).thenReturn("test-verification");
    when(verified.reason()).thenReturn("verified");
    when(verifiedAgentClient.evaluate(any(), anyInt(), any(), any())).thenReturn(verified);
  }

  @Test
  void approvalRequiresOwnedRequestAndRegeneratesDependentsWithRoundHistory() throws Exception {
    AtomicInteger reviews = new AtomicInteger();
    when(reviewAiClient.review(any())).thenAnswer(invocation -> {
      AiReviewResult result = org.mockito.Mockito.mock(AiReviewResult.class);
      when(result.promptVersion()).thenReturn("test-review");
      when(result.findings()).thenReturn(reviews.getAndIncrement() == 0
          ? List.of(new AiProposedFinding(
              "BUG", "HIGH", "Fix App", "Update App", APP, 1, 1,
              null, "AI", "fix application", 0.9))
          : List.of());
      return result;
    });

    String token = register();
    String id = create(token, validBody("fix-" + UUID.randomUUID()));
    JsonNode awaiting = poll(token, id);
    String findingId = awaiting.get("findings").get(0).get("id").asText();
    String beforeApp = Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 1).resolve(APP));
    String beforeHelper = Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 1).resolve(HELPER));

    mockMvc.perform(post("/api/v1/generations/" + id + "/fix-approvals")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + findingId + "\"],\"apiKey\":\"sk-test-key\"}"))
        .andExpect(status().isForbidden());
    assertThat(Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 1).resolve(APP))).isEqualTo(beforeApp);
    assertThat(fileVersions.findByGenerationIdAndRoundNumberOrderByFilePathAsc(
        UUID.fromString(id), 1)).isEmpty();

    mockMvc.perform(post("/api/v1/generations/" + id + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isAccepted());
    mockMvc.perform(post("/api/v1/generations/" + id + "/fix-approvals")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + findingId + "\"],\"apiKey\":\"sk-test-key\"}"))
        .andExpect(status().isAccepted());

    JsonNode settled = poll(token, id);
    assertThat(settled.get("status").asText()).isEqualTo("REVIEWED");
    assertThat(Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 2).resolve(APP))).isNotEqualTo(beforeApp);
    assertThat(Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 2).resolve(HELPER))).isNotEqualTo(beforeHelper);
    assertThat(fileVersions.findByGenerationIdAndRoundNumberOrderByFilePathAsc(
        UUID.fromString(id), 2)).hasSize(2);
    assertThat(executions.findByGenerationIdOrderByCreatedAtAsc(UUID.fromString(id)).stream()
        .filter(execution -> execution.getRoundNumber() == 2)
        .map(AgentExecution::getAgentType))
        .contains(AgentType.CODING, AgentType.VERIFIED, AgentType.REVIEW, AgentType.RATING);

    mockMvc.perform(post("/api/v1/generations/" + id + "/fix-rounds/2/revert")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    poll(token, id);
    assertThat(Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 2).resolve(APP))).isEqualTo(beforeApp);
    assertThat(Files.readString(storage.generationWorkspaceDir(
        UUID.fromString(id), 2).resolve(HELPER))).isEqualTo(beforeHelper);
    assertThat(fileVersions.findByGenerationIdAndRoundNumberOrderByFilePathAsc(
        UUID.fromString(id), 2)).allMatch(GenerationFileVersion::isReverted);
  }

  @Test
  void roundFiveIsCappedAndRevertRestoresExactContent() throws Exception {
    when(reviewAiClient.review(any())).thenAnswer(invocation -> {
      AiReviewResult result = org.mockito.Mockito.mock(AiReviewResult.class);
      when(result.promptVersion()).thenReturn("test-review");
      when(result.findings()).thenReturn(List.of(new AiProposedFinding(
          "BUG", "HIGH", "Keep finding", "Still open", APP, 1, 1,
          null, "AI", "keep", 0.9)));
      return result;
    });

    String token = register();
    String id = create(token, validBody("cap-" + UUID.randomUUID()));
    JsonNode awaiting = poll(token, id);
    String findingId = awaiting.get("findings").get(0).get("id").asText();
    for (int round = 2; round <= 5; round++) {
      mockMvc.perform(post("/api/v1/generations/" + id + "/fix-requests")
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isAccepted());
      mockMvc.perform(post("/api/v1/generations/" + id + "/fix-approvals")
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"findingIds\":[\"" + findingId + "\"],\"apiKey\":\"sk-test-key\"}"))
          .andExpect(status().isAccepted());
      awaiting = poll(token, id);
      assertThat(awaiting.get("iteration").asInt()).isEqualTo(round);
      if (round < 5) {
        findingId = awaiting.get("findings").get(0).get("id").asText();
      }
    }

    mockMvc.perform(post("/api/v1/generations/" + id + "/fix-approvals")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + findingId + "\"],\"apiKey\":\"sk-test-key\"}"))
        .andExpect(status().isConflict());
    assertThat(mockMvc.perform(get("/api/v1/generations/" + id)
            .header("Authorization", "Bearer " + token)).andReturn()
        .getResponse().getContentAsString()).contains(findingId);
  }

  @Test
  void failedReviewRetryRunsOnlyReviewStageAndSecretsStayOutOfRows() throws Exception {
    AtomicInteger reviewCalls = new AtomicInteger();
    when(reviewAiClient.review(any())).thenAnswer(invocation -> {
      if (reviewCalls.getAndIncrement() == 0) {
        throw new com.verireview.agent.AiServiceException(
            com.verireview.agent.AiServiceException.Kind.UNAVAILABLE, "review unavailable");
      }
      AiReviewResult result = org.mockito.Mockito.mock(AiReviewResult.class);
      when(result.promptVersion()).thenReturn("test-review");
      when(result.findings()).thenReturn(List.of());
      return result;
    });
    String token = register();
    String id = create(token, validBody("retry-" + UUID.randomUUID()));
    JsonNode failed = poll(token, id);
    UUID generationId = UUID.fromString(id);
    List<AgentExecution> before = executions.findByGenerationIdOrderByCreatedAtAsc(generationId);
    UUID failedReview = before.stream()
        .filter(execution -> execution.getAgentType() == AgentType.REVIEW
            && execution.getStatus().name().equals("FAILED"))
        .findFirst().orElseThrow().getId();

    mockMvc.perform(post("/api/v1/generations/" + id + "/agent-executions/"
            + failedReview + "/retry")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted());
    JsonNode settled = poll(token, id);
    assertThat(settled.get("status").asText()).isEqualTo("REVIEWED");
    List<AgentExecution> after = executions.findByGenerationIdOrderByCreatedAtAsc(generationId);
    assertThat(after.stream().filter(e -> e.getAgentType() == AgentType.PLANNING).count())
        .isEqualTo(before.stream().filter(e -> e.getAgentType() == AgentType.PLANNING).count());
    assertThat(after.stream().filter(e -> e.getAgentType() == AgentType.CODING).count())
        .isEqualTo(before.stream().filter(e -> e.getAgentType() == AgentType.CODING).count());
    assertThat(after.stream().filter(e -> e.getAgentType() == AgentType.VERIFIED).count())
        .isEqualTo(before.stream().filter(e -> e.getAgentType() == AgentType.VERIFIED).count());
    assertThat(after.stream().filter(e -> e.getAgentType() == AgentType.REVIEW).count())
        .isEqualTo(before.stream().filter(e -> e.getAgentType() == AgentType.REVIEW).count() + 1);
    assertThat(failed.toString()).doesNotContain("sk-test-key", "s3cr3t-db-pw");
    List<String> persisted = jdbc.queryForList(
        "SELECT COALESCE(error, '') FROM agent_executions", String.class);
    assertThat(persisted.toString()).doesNotContain("sk-test-key", "s3cr3t-db-pw");
  }

  @Test
  void downloadRemainsBlockedWithFindings() throws Exception {
    when(reviewAiClient.review(any())).thenAnswer(invocation -> {
      AiReviewResult result = org.mockito.Mockito.mock(AiReviewResult.class);
      when(result.promptVersion()).thenReturn("test-review");
      when(result.findings()).thenReturn(List.of(new AiProposedFinding(
          "BUG", "HIGH", "Keep open", "Keep open", APP, 1, 1,
          null, "AI", "keep", 0.9)));
      return result;
    });
    String token = register();
    String id = create(token, validBody("download-" + UUID.randomUUID()));
    poll(token, id);
    mockMvc.perform(get("/api/v1/generations/" + id + "/download")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
  }

  private String create(String token, String body) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private JsonNode poll(String token, String id) throws Exception {
    long deadline = System.currentTimeMillis() + 45_000;
    while (true) {
      JsonNode root = objects.readTree(mockMvc.perform(get("/api/v1/generations/" + id)
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
      String state = root.get("status").asText();
      if (state.equals("REVIEWED") || state.equals("AWAITING_FIX_APPROVAL")
          || state.equals("FAILED")) {
        return root;
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Generation did not settle: " + state);
      }
      Thread.sleep(200);
    }
  }

  private String validBody(String name) {
    return "{\"name\":\"" + name + "\",\"requirement\":\"A minimal todo API with tests\","
        + "\"description\":\"demo\",\"backend\":\"PYTHON_FASTAPI\",\"frontend\":\"NONE\","
        + "\"database\":\"POSTGRESQL\",\"databaseConfig\":{\"host\":\"localhost\","
        + "\"port\":5432,\"name\":\"todos\",\"username\":\"app\","
        + "\"password\":\"s3cr3t-db-pw\"},\"aiConfig\":{\"provider\":\"OPENROUTER\","
        + "\"apiKey\":\"sk-test-key\",\"model\":\"test/model\"}}";
  }

  private String register() throws Exception {
    String email = "fix-" + UUID.randomUUID() + "@example.com";
    String body = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Fix\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
        ;
    return objects.readTree(body).get("accessToken").asText();
  }
}
