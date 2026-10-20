package com.verireview.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.agent.GenerationAiClient;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.project.ProjectFileRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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

/**
 * Generate wizard configuration contract: stack/auth/integration validation and
 * persistence, the step-4 scaffold preview, the workspace browse endpoint, and
 * the approval-gated restructure flow (propose → apply → revert).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationConfigTest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private GenerationService generations;
  @Autowired private GenerationRepository generationRows;
  @Autowired private ProjectFileRepository projectFiles;
  @Autowired private ProjectStorage storage;
  @MockitoBean private GenerationAiClient generationAiClient;

  @Test
  void draftPersistsAuthAndIntegrations() throws Exception {
    String token = access(register());
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("wizard-" + UUID.randomUUID(), "NODEJS", "NEXTJS", "SQLITE",
                "JWT", "[\"EMAIL\",\"FILE_STORAGE_S3\"]", true)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.auth").value("JWT"))
        .andExpect(jsonPath("$.integrations[0]").value("EMAIL"))
        .andExpect(jsonPath("$.integrations[1]").value("FILE_STORAGE_S3"))
        .andExpect(jsonPath("$.backend").value("NODEJS"))
        .andExpect(jsonPath("$.frontend").value("NEXTJS"))
        .andExpect(jsonPath("$.database").value("SQLITE"))
        .andReturn();
    String response = result.getResponse().getContentAsString();
    assertThat(response).doesNotContain("not-a-real-secret");
    assertThat(response).doesNotContain("sk-test-key");
  }

  @Test
  void rejectsIncoherentSelections() throws Exception {
    String token = access(register());
    // No backend and no frontend.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("none-" + UUID.randomUUID(), "NONE", "NONE", "NONE",
                "NONE", "[]", true)))
        .andExpect(status().isBadRequest());

    // Auth needs a backend to protect.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("auth-" + UUID.randomUUID(), "NONE", "REACT_TYPESCRIPT", "NONE",
                "JWT", "[]", true)))
        .andExpect(status().isBadRequest());

    // Two payment providers is ambiguous.
    mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("pay-" + UUID.randomUUID(), "NODEJS", "NONE", "NONE",
                "NONE", "[\"STRIPE\",\"RAZORPAY\"]", true)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void planPreviewIsStackAwareAndPersistsNothing() throws Exception {
    String token = access(register());
    mockMvc.perform(post("/api/v1/generations/plan-preview")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requirement\":\"A shop with Google sign-in and email.\","
                + "\"backend\":\"NODEJS\",\"frontend\":\"NEXTJS\",\"database\":\"SQLITE\","
                + "\"auth\":\"OAUTH_GOOGLE\",\"integrations\":[\"EMAIL\",\"FILE_STORAGE_S3\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files[?(@.path == 'frontend/app/page.tsx')]").exists())
        .andExpect(jsonPath("$.files[?(@.path == 'auth/google-callback.js')]").exists())
        .andExpect(jsonPath("$.files[?(@.path == 'integrations/email.js')]").exists())
        .andExpect(jsonPath("$.files[?(@.path == 'db/schema.sql')]").exists())
        .andExpect(jsonPath("$.notes").value(
            org.hamcrest.Matchers.containsString("Scaffold preview only")));
  }

  @Test
  void sendToReviewMaterializesAProjectAndIsIdempotent() throws Exception {
    String token = access(register());
    String id = create(token, body("handoff-" + UUID.randomUUID(), "JAVA_SPRING_BOOT",
        "REACT_TYPESCRIPT", "POSTGRESQL", "JWT", "[\"EMAIL\"]", true));
    UUID generationId = UUID.fromString(id);

    Path workspace = storage.generationWorkspaceDir(generationId, 1);
    Files.createDirectories(workspace.resolve("src/main/java/app"));
    Files.writeString(workspace.resolve("src/main/java/app/Application.java"),
        "package app;\\nclass Application {}\\n");
    Files.writeString(workspace.resolve("README.md"), "# generated\\n");
    generations.recordArtifact(generationId, 1, 2, 40, "b".repeat(64), workspace.toString());

    Generation row = generationRows.findById(generationId).orElseThrow();
    row.setStatus(GenerationStatus.REVIEWED);
    generationRows.saveAndFlush(row);

    MvcResult first = mockMvc.perform(post("/api/v1/generations/" + id + "/send-to-review")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").isNotEmpty())
        .andReturn();
    String projectId = objects.readTree(first.getResponse().getContentAsString())
        .get("projectId").asText();
    assertThat(projectFiles.findByProjectId(UUID.fromString(projectId)))
        .extracting(file -> file.getPath())
        .contains("src/main/java/app/Application.java", "README.md");
    assertThat(Files.exists(storage.projectDir(UUID.fromString(projectId))
        .resolve("src/main/java/app/Application.java"))).isTrue();

    // A second click must reuse the linked project, not create a duplicate.
    mockMvc.perform(post("/api/v1/generations/" + id + "/send-to-review")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId));
  }

  @Test
  void restructureProposeApplyRevertKeepsOriginal() throws Exception {
    String token = access(register());
    String id = create(token, body("restructure-" + UUID.randomUUID(), "JAVA_SPRING_BOOT",
        "REACT_TYPESCRIPT", "POSTGRESQL", "JWT", "[\"DOCKER_CI\"]", true));
    UUID generationId = UUID.fromString(id);

    Path workspace = storage.generationWorkspaceDir(generationId, 1);
    Files.createDirectories(workspace.resolve("src/main/java/app"));
    Files.writeString(workspace.resolve("src/main/java/app/UserController.java"),
        "package app;\nclass UserController {}\n");
    Files.writeString(workspace.resolve("src/main/java/app/UserService.java"),
        "package app;\nclass UserService {}\n");
    Files.writeString(workspace.resolve("README.md"), "# demo\n");
    generations.recordArtifact(generationId, 1, 3, 60, "a".repeat(64), workspace.toString());

    // Browse sees the real files.
    mockMvc.perform(get("/api/v1/generations/" + id + "/files")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.path == 'src/main/java/app/UserController.java')]").exists());

    MvcResult proposed = mockMvc.perform(post("/api/v1/generations/" + id + "/restructure/proposals")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"preset\":\"MVC\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PROPOSED"))
        .andExpect(jsonPath("$.moves").isNotEmpty())
        .andReturn();
    JsonNode proposal = objects.readTree(proposed.getResponse().getContentAsString());
    String proposalId = proposal.get("id").asText();

    // Nothing changes before approval.
    assertThat(Files.exists(workspace.resolve("src/main/java/app/UserController.java"))).isTrue();

    mockMvc.perform(post("/api/v1/generations/" + id + "/restructure/proposals/"
            + proposalId + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPLIED"))
        .andExpect(jsonPath("$.canRevert").value(true));
    assertThat(Files.exists(workspace.resolve("controllers/UserController.java"))).isTrue();
    assertThat(Files.exists(workspace.resolve("src/main/java/app/UserController.java"))).isFalse();

    mockMvc.perform(post("/api/v1/generations/" + id + "/restructure/revert")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVERTED"));
    assertThat(Files.exists(workspace.resolve("src/main/java/app/UserController.java"))).isTrue();
    // Revert replaces the tree instead of merging, so moved copies disappear.
    assertThat(Files.exists(workspace.resolve("controllers/UserController.java"))).isFalse();
  }

  private String body(String name, String backend, String frontend, String database,
      String auth, String integrations, boolean draft) {
    String dbConfig = "NONE".equals(database) ? ""
        : "\"databaseConfig\":{\"host\":\"localhost\",\"port\":5432,\"name\":\"demo\","
            + "\"username\":\"app\",\"password\":\"not-a-real-secret\"},";
    return "{\"name\":\"" + name + "\","
        + "\"requirement\":\"Create a task management application with clear ownership.\","
        + "\"description\":\"demo\","
        + "\"backend\":\"" + backend + "\",\"frontend\":\"" + frontend + "\","
        + "\"database\":\"" + database + "\",\"auth\":\"" + auth + "\","
        + "\"integrations\":" + integrations + ","
        + dbConfig
        + "\"aiConfig\":{\"provider\":\"OPENROUTER\",\"model\":\"test/model\"},"
        + "\"draft\":" + draft + "}";
  }

  private String create(String token, String body) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/generations")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private String register() throws Exception {
    String email = "wizard-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\","
                + "\"displayName\":\"Wizard\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
