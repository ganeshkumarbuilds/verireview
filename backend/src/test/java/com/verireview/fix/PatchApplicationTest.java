package com.verireview.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.verireview.agent.CodingAiClient;
import com.verireview.agent.dto.AiCodingResult;

@SpringBootTest
@AutoConfigureMockMvc
class PatchApplicationTest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private UserRepository users;
  @Autowired private ProjectRepository projects;
  @Autowired private ReviewRepository reviews;
  @Autowired private FindingRepository findings;
  @Autowired private FixRequestRepository fixRequests;
  @Autowired private PatchRepository patches;
  @Autowired private ProjectStorage storage;
  @MockitoBean private CodingAiClient codingAiClient;

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
  void stubCodingAi() {
    when(codingAiClient.coding(any())).thenAnswer(inv -> {
      var arg = inv.getArgument(0);
      if (arg == null) return null;
      var req = (com.verireview.agent.dto.AiCodingRequest) arg;
      return new AiCodingResult(
          req.fixRequestId(),
          "coding",
          "coding/v1",
          VALID_DIFF,
          1,
          1,
          0,
          "Fix SQL concat via prepared statement",
          "ok");
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
  void successfulApplication() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Fixture fix = createFixture(owner, "// placeholder for finding: SQL concat\n// finding: SQL concat\nthird\n", "src/File.java");

    // Propose patch via API to get a real PROPOSED patch
    String fixId = createFixRequest(token, fix.findingId);
    String patchId = proposePatch(token, fixId);

    // Verify file before
    Path projectDir = storage.projectDir(fix.project.getId());
    String before = Files.readString(projectDir.resolve("src/File.java"), StandardCharsets.UTF_8);
    assertThat(before).isEqualTo("// placeholder for finding: SQL concat\n// finding: SQL concat\nthird\n");

    // Apply
    mockMvc.perform(post("/api/v1/patches/" + patchId + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPLIED"))
        .andExpect(jsonPath("$.id").value(patchId));

    String after = Files.readString(projectDir.resolve("src/File.java"), StandardCharsets.UTF_8);
    assertThat(after).contains("FIX");
    assertThat(after).contains("third");

    Patch patch = patches.findById(UUID.fromString(patchId)).orElseThrow();
    assertThat(patch.getStatus()).isEqualTo(PatchStatus.APPLIED);
  }

  @Test
  void nonOwnerRejection() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    User owner = tokenOwner(ownerToken);
    Fixture fix = createFixture(owner, "A\nB\n", "src/A.java");
    String fixId = createFixRequest(ownerToken, fix.findingId);
    String patchId = proposePatch(ownerToken, fixId);

    mockMvc.perform(post("/api/v1/patches/" + patchId + "/apply")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    // Ensure not applied
    Patch patch = patches.findById(UUID.fromString(patchId)).orElseThrow();
    assertThat(patch.getStatus()).isEqualTo(PatchStatus.PROPOSED);
  }

  @Test
  void malformedDiffRejected() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Fixture fix = createFixture(owner, "Hello\n", "src/Bad.java");
    String fixId = createFixRequest(token, fix.findingId);
    // Manually create malformed patch
    FixRequest fr = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    Patch bad = new Patch(fr, "not a diff");
    bad.setProject(fix.project);
    bad.setFilesChanged(1);
    bad.setStatus(PatchStatus.PROPOSED);
    patches.save(bad);

    mockMvc.perform(post("/api/v1/patches/" + bad.getId() + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());

    Patch still = patches.findById(bad.getId()).orElseThrow();
    assertThat(still.getStatus()).isEqualTo(PatchStatus.PROPOSED);
  }

  @Test
  void traversalAttemptRejected() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Fixture fix = createFixture(owner, "content\n", "src/Okay.java");
    String fixId = createFixRequest(token, fix.findingId);
    FixRequest fr = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    String traversalDiff = """
        diff --git a/../../etc/passwd b/../../etc/passwd
        --- a/../../etc/passwd
        +++ b/../../etc/passwd
        @@ -1,3 +1,4 @@
         line1
        +hacked
         line2
        """;
    Patch bad = new Patch(fr, traversalDiff);
    bad.setProject(fix.project);
    bad.setStatus(PatchStatus.PROPOSED);
    bad.setFilesChanged(1);
    patches.save(bad);

    Path projectDir = storage.projectDir(fix.project.getId());
    String before = Files.readString(projectDir.resolve("src/Okay.java"), StandardCharsets.UTF_8);

    mockMvc.perform(post("/api/v1/patches/" + bad.getId() + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());

    String after = Files.readString(projectDir.resolve("src/Okay.java"), StandardCharsets.UTF_8);
    assertThat(after).isEqualTo(before);
    assertThat(patches.findById(bad.getId()).orElseThrow().getStatus()).isEqualTo(PatchStatus.PROPOSED);
  }

  @Test
  void wrongPatchStatusRejected() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Fixture fix = createFixture(owner, "// placeholder for finding: SQL concat\n// finding: SQL concat\n", "src/C.java");
    String fixId = createFixRequest(token, fix.findingId);
    // Use a valid diff for this file path
    FixRequest fr = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    String validDiff = """
        diff --git a/src/C.java b/src/C.java
        --- a/src/C.java
        +++ b/src/C.java
        @@ -1,2 +1,3 @@
         // placeholder for finding: SQL concat
        +// FIX
         // finding: SQL concat
        """;
    Patch patch = new Patch(fr, validDiff);
    patch.setProject(fix.project);
    patch.setStatus(PatchStatus.PROPOSED);
    patch.setFilesChanged(1);
    patch.setAdditions(1);
    patches.save(patch);
    String patchId = patch.getId().toString();

    // First apply succeeds
    mockMvc.perform(post("/api/v1/patches/" + patchId + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    // Second apply should conflict
    mockMvc.perform(post("/api/v1/patches/" + patchId + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
  }

  @Test
  void failedApplicationPreservesOriginalSnapshot() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Fixture fix = createFixture(owner, "Alpha\nBeta\nGamma\n", "src/Preserve.java");
    String fixId = createFixRequest(token, fix.findingId);
    FixRequest fr = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    // Diff expects wrong context (Beta mismatch)
    String failingDiff = """
        diff --git a/src/Preserve.java b/src/Preserve.java
        --- a/src/Preserve.java
        +++ b/src/Preserve.java
        @@ -1,3 +1,4 @@
         Alpha
        -WrongContext
        +Fixed
         Gamma
        """;
    Patch bad = new Patch(fr, failingDiff);
    bad.setProject(fix.project);
    bad.setStatus(PatchStatus.PROPOSED);
    bad.setFilesChanged(1);
    patches.save(bad);

    Path projectDir = storage.projectDir(fix.project.getId());
    String before = Files.readString(projectDir.resolve("src/Preserve.java"), StandardCharsets.UTF_8);

    mockMvc.perform(post("/api/v1/patches/" + bad.getId() + "/apply")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());

    String after = Files.readString(projectDir.resolve("src/Preserve.java"), StandardCharsets.UTF_8);
    assertThat(after).isEqualTo(before);
    assertThat(patches.findById(bad.getId()).orElseThrow().getStatus()).isEqualTo(PatchStatus.PROPOSED);
  }

  // Helpers

  private record Fixture(Project project, String findingId) {}

  private Fixture createFixture(User owner, String fileContent, String filePath) throws Exception {
    Project project = projects.save(new Project(owner, "proj-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    lastProject = project;
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.HIGH, FindingSource.DETERMINISTIC, "SQL concat");
    finding.setFilePath(filePath);
    finding.setLineStart(1);
    String findingId = findings.save(finding).getId().toString();
    // Create file on disk via storage
    Path projectDir = storage.projectDir(project.getId());
    Files.createDirectories(projectDir.resolve(filePath).getParent());
    Files.writeString(projectDir.resolve(filePath), fileContent, StandardCharsets.UTF_8);
    // Also update storage_ref
    project.setStorageRef(projectDir.toString());
    projects.save(project);
    // Also update ProjectFile metadata to satisfy DB? Not needed for storage, but create entry
    // Use a simple entry - not strictly needed but keep consistency
    return new Fixture(project, findingId);
  }

  private String createFixRequest(String token, String findingId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private String proposePatch(String token, String fixId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private User tokenOwner(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }

  private String register() throws Exception {
    String email = "apply-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Apply\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
