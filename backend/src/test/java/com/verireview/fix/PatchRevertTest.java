package com.verireview.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.agent.CodingAiClient;
import com.verireview.agent.dto.AiCodingRequest;
import com.verireview.agent.dto.AiCodingResult;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Revert restores the exact previous file content from the persisted pre-apply
 * snapshot (AGENTS.md rule 20: destructive changes are reversible).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatchRevertTest extends AbstractPersistenceTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objects;
  @Autowired UserRepository users;
  @Autowired ProjectRepository projects;
  @Autowired ReviewRepository reviews;
  @Autowired FindingRepository findings;
  @Autowired PatchRepository patches;
  @Autowired PatchSnapshotRepository snapshots;
  @Autowired ProjectStorage storage;
  @MockitoBean CodingAiClient codingAiClient;

  private Project lastProject;

  private static final String ORIGINAL =
      "// placeholder for finding: SQL concat\n// finding: SQL concat\nthird\n";

  private static final String VALID_DIFF =
      "diff --git a/src/File.java b/src/File.java\n"
          + "--- a/src/File.java\n"
          + "+++ b/src/File.java\n"
          + "@@ -1,3 +1,4 @@\n"
          + " // placeholder for finding: SQL concat\n"
          + "+// FIX (proposed, not applied): SQL concat\n"
          + " // finding: SQL concat";

  @BeforeEach
  void stubCodingAi() {
    when(codingAiClient.coding(any())).thenAnswer(inv -> {
      AiCodingRequest req = inv.getArgument(0);
      return new AiCodingResult(
          req.fixRequestId(), "coding", "coding/v1", VALID_DIFF, 1, 1, 0, "Fix", "ok");
    });
  }

  @AfterEach
  void cleanup() {
    if (lastProject != null) {
      storage.deleteQuietly(storage.projectDir(lastProject.getId()));
      lastProject = null;
    }
  }

  @Test
  void revertRestoresPreviousContent() throws Exception {
    String token = access(register());
    Fixture fixture = createFixture(token);

    MvcResult proposed = mockMvc.perform(post("/api/v1/fix-requests/" + fixture.fixRequestId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andReturn();
    String patchId = objects.readTree(proposed.getResponse().getContentAsString()).get("id").asText();

    Path file = storage.projectDir(fixture.project.getId()).resolve("src/File.java");

    mockMvc.perform(post("/api/v1/patches/" + patchId + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPLIED"));

    assertThat(snapshots.findByPatchIdOrderByFilePathAsc(UUID.fromString(patchId)))
        .hasSize(1);
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("FIX");

    mockMvc.perform(post("/api/v1/patches/" + patchId + "/revert")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVERTED"));

    assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(ORIGINAL);
    assertThat(patches.findById(UUID.fromString(patchId)).orElseThrow().getStatus())
        .isEqualTo(PatchStatus.REVERTED);

    // Reverting again is a conflict, not a crash.
    mockMvc.perform(post("/api/v1/patches/" + patchId + "/revert")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
  }

  // Helpers

  private record Fixture(Project project, String findingId, String fixRequestId) {}

  private Fixture createFixture(String token) throws Exception {
    User owner = tokenOwner(token);
    Project project = projects.save(
        new Project(owner, "revert-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    lastProject = project;
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.HIGH,
        FindingSource.DETERMINISTIC, "SQL concat");
    finding.setFilePath("src/File.java");
    finding.setLineStart(1);
    String findingId = findings.save(finding).getId().toString();

    Path projectDir = storage.projectDir(project.getId());
    Files.createDirectories(projectDir.resolve("src"));
    Files.writeString(projectDir.resolve("src/File.java"), ORIGINAL, StandardCharsets.UTF_8);
    project.setStorageRef(projectDir.toString());
    projects.save(project);

    MvcResult created = mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isAccepted())
        .andReturn();
    String fixRequestId = objects.readTree(created.getResponse().getContentAsString()).get("id").asText();
    return new Fixture(project, findingId, fixRequestId);
  }

  private User tokenOwner(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }

  private String register() throws Exception {
    String email = "revert-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Revert\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String body) throws Exception {
    return objects.readTree(body).get("accessToken").asText();
  }
}
