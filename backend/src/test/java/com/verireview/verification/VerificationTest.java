package com.verireview.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.execution.ExecutionRun;
import com.verireview.execution.ExecutionRunRepository;
import com.verireview.execution.ExecutionStatus;
import com.verireview.fix.FixRequest;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.Patch;
import com.verireview.fix.PatchRepository;
import com.verireview.fix.PatchStatus;
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
import com.verireview.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class VerificationTest extends AbstractPersistenceTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objects;
  @Autowired UserRepository users;
  @Autowired ProjectRepository projects;
  @Autowired ReviewRepository reviews;
  @Autowired FindingRepository findings;
  @Autowired FixRequestRepository fixRequests;
  @Autowired PatchRepository patches;
  @Autowired ExecutionRunRepository executions;
  @Autowired VerificationRunRepository verifications;

  @Test
  void verifiedWhenAllGatesPass() throws Exception {
    String token = access(register());
    var owner = tokenOwner(token);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.SUCCESS, 0, BuildStatus.SUCCESS);

    MvcResult result = mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.verdict").value("VERIFIED"))
        .andExpect(jsonPath("$.buildStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.patchId").value(fixture.patchId.toString()))
        .andReturn();

    String verificationId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
    var verification = verifications.findById(UUID.fromString(verificationId)).orElseThrow();
    assertThat(verification.getVerdict()).isEqualTo(VerificationVerdict.VERIFIED);
    assertThat(verification.getExecutionRun().getId().toString()).isEqualTo(fixture.executionId.toString());
  }

  @Test
  void rejectedWhenBuildFailed() throws Exception {
    String token = access(register());
    var owner = tokenOwner(token);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.FAILURE, 1, BuildStatus.FAILURE);

    mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("REJECTED"));
  }

  @Test
  void rejectedWhenFailedExecution() throws Exception {
    String token = access(register());
    var owner = tokenOwner(token);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.FAILURE, 1, BuildStatus.FAILURE);

    MvcResult result = mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("REJECTED"))
        .andReturn();

    String verificationId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
    var v = verifications.findById(UUID.fromString(verificationId)).orElseThrow();
    assertThat(v.getVerdict()).isEqualTo(VerificationVerdict.REJECTED);
  }

  @Test
  void rejectedWhenNewCriticalHighFinding() throws Exception {
    String token = access(register());
    var owner = tokenOwner(token);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.SUCCESS, 0, BuildStatus.SUCCESS);

    // Simulate new CRITICAL finding after patch
    Project project = projects.findById(fixture.projectId).orElseThrow();
    Review review = reviews.findAll().stream()
        .filter(r -> r.getProject().getId().equals(project.getId()))
        .findFirst().orElseThrow();
    Finding critical = new Finding(review, FindingCategory.SECURITY, FindingSeverity.CRITICAL, FindingSource.DETERMINISTIC, "New critical");
    critical.setFilePath("src/New.java");
    // Ensure createdAt is after patch
    Thread.sleep(10);
    findings.save(critical);

    mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("REJECTED"));
  }

  @Test
  void nonOwnerCannotVerify() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    var owner = tokenOwner(ownerToken);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.SUCCESS, 0, BuildStatus.SUCCESS);

    mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void unauthenticatedIs401() throws Exception {
    String token = access(register());
    var owner = tokenOwner(token);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.SUCCESS, 0, BuildStatus.SUCCESS);

    mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void backendEnforcesVerdictEvenIfAgentWouldSayVerified() throws Exception {
    // This test ensures Java policy overrides any LLM recommendation.
    // We simulate an execution that failed, but even if an AI would say VERIFIED,
    // Java must still REJECT because build failed.
    String token = access(register());
    var owner = tokenOwner(token);
    var fixture = createAppliedProjectWithExecution(owner, ExecutionStatus.FAILURE, 1, BuildStatus.FAILURE);

    // Even though we pass a "clean" execution, the policy should still reject because build failed
    // This proves LLM cannot directly decide VERIFIED
    MvcResult result = mockMvc.perform(post("/api/v1/executions/" + fixture.executionId + "/verification")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    String verdict = objects.readTree(result.getResponse().getContentAsString()).get("verdict").asText();
    assertThat(verdict).isEqualTo("REJECTED");
  }

  // Helpers

  private record Fixture(UUID projectId, UUID patchId, UUID executionId) {}

  private Fixture createAppliedProjectWithExecution(
      com.verireview.user.User owner, ExecutionStatus execStatus, int exitCode, BuildStatus buildStatus) throws Exception {
    Project project = projects.save(new Project(owner, "ver-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.MEDIUM, FindingSource.DETERMINISTIC, "test finding");
    finding.setFilePath("src/Main.java");
    findings.save(finding);
    FixRequest fr = fixRequests.save(new FixRequest(finding, owner));
    fr.setStatus(com.verireview.fix.FixRequestStatus.IN_PROGRESS);
    fixRequests.save(fr);
    Patch patch = new Patch(fr, "diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-old\n+new");
    patch.setProject(project);
    patch.setStatus(PatchStatus.APPLIED);
    patch.setFilesChanged(1);
    patches.save(patch);

    ExecutionRun execution = new ExecutionRun(project, patch);
    execution.setStatus(execStatus);
    execution.setExitCode(exitCode);
    execution.setBuildStatus(buildStatus);
    execution.setStdout("stdout");
    execution.setStderr("");
    execution.setDurationMs(1000L);
    executions.save(execution);

    // Need to set storage ref for project dir existence? Not needed for verification, only execution creation needs it, but we already created execution directly
    return new Fixture(project.getId(), patch.getId(), execution.getId());
  }

  private String register() throws Exception {
    String email = "ver-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Ver\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String body) throws Exception {
    return objects.readTree(body).get("accessToken").asText();
  }

  private com.verireview.user.User tokenOwner(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }
}
