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
import com.verireview.check.CheckFeature;
import com.verireview.check.CheckRunRepository;
import com.verireview.execution.SandboxRunner;
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
import com.verireview.review.FindingStatus;
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
 * The find -> ask permission -> fix -> re-check flow.
 *
 * <p>Proves (a) the backend refuses to fix without an approved FixRequest, and
 * (b) after a fix it automatically re-checks and reports a before/after summary.
 * (c) When the sandbox is disabled it never claims verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FixRunTest extends AbstractPersistenceTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objects;
  @Autowired UserRepository users;
  @Autowired ProjectRepository projects;
  @Autowired ReviewRepository reviews;
  @Autowired FindingRepository findings;
  @Autowired FixRequestRepository fixRequests;
  @Autowired PatchRepository patches;
  @Autowired CheckRunRepository checkRuns;
  @Autowired ProjectStorage storage;
  @MockitoBean CodingAiClient codingAiClient;
  @MockitoBean SandboxRunner sandboxRunner;

  private Project lastProject;

  private static final String VALID_DIFF =
      "diff --git a/src/File.java b/src/File.java\n"
          + "--- a/src/File.java\n"
          + "+++ b/src/File.java\n"
          + "@@ -1,3 +1,4 @@\n"
          + " // placeholder for finding: SQL concat\n"
          + "+// FIX (proposed, not applied): SQL concat\n"
          + " // finding: SQL concat";

  @BeforeEach
  void stubCodingAiAndSandbox() {
    when(codingAiClient.coding(any())).thenAnswer(inv -> {
      AiCodingRequest req = inv.getArgument(0);
      return new AiCodingResult(
          req.fixRequestId(), "coding", "coding/v1", VALID_DIFF, 1, 1, 0,
          "Fix SQL concat via prepared statement", "ok");
    });
    when(sandboxRunner.executeBuild(any(Path.class)))
        .thenReturn(new SandboxRunner.ExecutionResult(0, "BUILD SUCCESS", "", 1200L, false));
  }

  @AfterEach
  void cleanup() {
    if (lastProject != null) {
      storage.deleteQuietly(storage.projectDir(lastProject.getId()));
      lastProject = null;
    }
  }

  @Test
  void fixRunRejectedWithoutApprovedFixRequest() throws Exception {
    String token = access(register());
    Fixture fixture = createFixture(token);

    mockMvc.perform(post("/api/v1/projects/" + fixture.project.getId() + "/fix-runs")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + fixture.findingId + "\"]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.status").value(409))
        .andExpect(jsonPath("$.error.code").exists());

    // No run was created and nothing was changed.
    assertThat(checkRuns.findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(
        fixture.project.getId(), CheckFeature.FIX)).isEmpty();
    Path file = storage.projectDir(fixture.project.getId()).resolve("src/File.java");
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("FIX");
  }

  @Test
  void consentThenFixThenAutomaticRecheck() throws Exception {
    String token = access(register());
    Fixture fixture = createFixture(token);

    // Consent step.
    mockMvc.perform(post("/api/v1/projects/" + fixture.project.getId() + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + fixture.findingId + "\"],\"scopeNote\":\"use prepared statement\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.created").value(1))
        .andExpect(jsonPath("$.fixRequestIds.length()").value(1));
    assertThat(fixRequests.findByFindingIdOrderByCreatedAtDesc(
        UUID.fromString(fixture.findingId))).hasSize(1);

    // Fix run + automatic re-check.
    MvcResult run = mockMvc.perform(post("/api/v1/projects/" + fixture.project.getId() + "/fix-runs")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + fixture.findingId + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.verificationVerdict").value("VERIFIED"))
        .andExpect(jsonPath("$.sandboxSkipped").value(false))
        .andExpect(jsonPath("$.summary.fixed").value(1))
        .andExpect(jsonPath("$.summary.stillOpen").value(0))
        .andExpect(jsonPath("$.changedFiles.length()").value(1))
        .andExpect(jsonPath("$.changedFiles[0].path").value("src/File.java"))
        .andExpect(jsonPath("$.steps.length()").value(2))
        .andReturn();
    String checkRunId = objects.readTree(run.getResponse().getContentAsString())
        .get("checkRunId").asText();

    // The file actually changed and the finding is verified fixed.
    Path file = storage.projectDir(fixture.project.getId()).resolve("src/File.java");
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("FIX");
    assertThat(findings.findById(UUID.fromString(fixture.findingId)).orElseThrow().getStatus())
        .isEqualTo(FindingStatus.VERIFIED_FIXED);

    // Reload the run and confirm it is still the source of truth.
    mockMvc.perform(get("/api/v1/projects/" + fixture.project.getId()
            + "/fix-runs/" + checkRunId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
  }

  @Test
  void sandboxDisabledNeverClaimsVerified() throws Exception {
    when(sandboxRunner.executeBuild(any(Path.class))).thenReturn(
        new SandboxRunner.ExecutionResult(0,
            "Docker sandbox is disabled in this environment; build/test execution was skipped",
            "", 1L, false, true));
    String token = access(register());
    Fixture fixture = createFixture(token);

    mockMvc.perform(post("/api/v1/projects/" + fixture.project.getId() + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + fixture.findingId + "\"]}"))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/api/v1/projects/" + fixture.project.getId() + "/fix-runs")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + fixture.findingId + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sandboxSkipped").value(true))
        .andExpect(jsonPath("$.verificationVerdict").value("PENDING"))
        .andExpect(jsonPath("$.summary.fixed").value(0))
        .andExpect(jsonPath("$.summary.stillOpen").value(1));

    // Pipeline VERIFY stage must be SKIPPED, never COMPLETED.
    mockMvc.perform(get("/api/v1/projects/" + fixture.project.getId() + "/pipeline")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stages[4].state").value("SKIPPED"))
        .andExpect(jsonPath("$.stages[4].detail").value("Not verified: sandbox unavailable"));
  }

  @Test
  void fixRunIsOwnerScoped() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    Fixture fixture = createFixture(ownerToken);

    mockMvc.perform(post("/api/v1/projects/" + fixture.project.getId() + "/fix-runs")
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"findingIds\":[\"" + fixture.findingId + "\"]}"))
        .andExpect(status().isNotFound());
  }

  // Helpers

  private record Fixture(Project project, String findingId) {}

  private Fixture createFixture(String token) throws Exception {
    User owner = tokenOwner(token);
    Project project = projects.save(
        new Project(owner, "fixrun-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    lastProject = project;
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.HIGH,
        FindingSource.DETERMINISTIC, "SQL concat");
    finding.setFilePath("src/File.java");
    finding.setLineStart(1);
    finding.setLineEnd(2);
    String findingId = findings.save(finding).getId().toString();

    Path projectDir = storage.projectDir(project.getId());
    Files.createDirectories(projectDir.resolve("src"));
    Files.writeString(projectDir.resolve("src/File.java"),
        "// placeholder for finding: SQL concat\n// finding: SQL concat\nthird\n",
        StandardCharsets.UTF_8);
    project.setStorageRef(projectDir.toString());
    projects.save(project);
    return new Fixture(project, findingId);
  }

  private User tokenOwner(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }

  private String register() throws Exception {
    String email = "fixrun-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"FixRun\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String body) throws Exception {
    return objects.readTree(body).get("accessToken").asText();
  }
}
