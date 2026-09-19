package com.verireview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.audit.AuditLogRepository;
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
import com.verireview.ingestion.ProjectStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ExecutionTest extends AbstractPersistenceTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objects;
  @Autowired UserRepository users;
  @Autowired ProjectRepository projects;
  @Autowired ReviewRepository reviews;
  @Autowired FindingRepository findings;
  @Autowired FixRequestRepository fixRequests;
  @Autowired PatchRepository patches;
  @Autowired ExecutionRunRepository executions;
  @Autowired ProjectStorage storage;
  @Autowired AuditLogRepository audits;
  @MockitoBean SandboxRunner sandboxRunner;

  private Project lastProject;

  @AfterEach
  void cleanup() {
    if (lastProject != null) {
      storage.deleteQuietly(storage.projectDir(lastProject.getId()));
      lastProject = null;
    }
  }

  @Test
  void successExecutionPersistsEvidenceAndAudits() throws Exception {
    String token = access(register());
    Fixture fix = createAppliedProject(token, "Hello.java", "public class Hello { public static void main(String[] args) { System.out.println(\"hi\"); }}", true);

    // Mock sandbox to return success
    SandboxRunner.ExecutionResult success = new SandboxRunner.ExecutionResult(0, "BUILD SUCCESS", "", 1234L, false);
    when(sandboxRunner.executeBuild(any(Path.class))).thenReturn(success);

    MvcResult result = mockMvc.perform(post("/api/v1/projects/" + fix.project.getId() + "/execute")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.exitCode").value(0))
        .andExpect(jsonPath("$.buildStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.stdout").value("BUILD SUCCESS"))
        .andReturn();

    String execId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
    ExecutionRun run = executions.findById(UUID.fromString(execId)).orElseThrow();
    assertThat(run.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    assertThat(run.getDurationMs()).isEqualTo(1234L);
    assertThat(audits.findAll().stream().anyMatch(a -> "EXECUTION_COMPLETED".equals(a.getAction()))).isTrue();

    // Cleanup check: sandbox workspace should be deleted (mocked, but verify delete not needed)
    // For real, verify storage still exists
    assertThat(Files.exists(storage.projectDir(fix.project.getId()))).isTrue();
  }

  @Test
  void failureExecutionCapturesFailure() throws Exception {
    String token = access(register());
    Fixture fix = createAppliedProject(token, "Fail.java", "public class Fail {}", false);

    SandboxRunner.ExecutionResult failure = new SandboxRunner.ExecutionResult(1, "", "BUILD FAILURE", 500L, false);
    when(sandboxRunner.executeBuild(any(Path.class))).thenReturn(failure);

    MvcResult result = mockMvc.perform(post("/api/v1/projects/" + fix.project.getId() + "/execute")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("FAILURE"))
        .andExpect(jsonPath("$.exitCode").value(1))
        .andExpect(jsonPath("$.buildStatus").value("FAILURE"))
        .andReturn();

    String execId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
    ExecutionRun run = executions.findById(UUID.fromString(execId)).orElseThrow();
    assertThat(run.getStderr()).isEqualTo("BUILD FAILURE");
  }

  @Test
  void timeoutIsHandledAndPersisted() throws Exception {
    String token = access(register());
    Fixture fix = createAppliedProject(token, "Sleep.java", "class Sleep {}", true);

    SandboxRunner.ExecutionResult timeout = new SandboxRunner.ExecutionResult(124, "partial", "timeout", 300000L, true);
    when(sandboxRunner.executeBuild(any(Path.class))).thenReturn(timeout);

    MvcResult result = mockMvc.perform(post("/api/v1/projects/" + fix.project.getId() + "/execute")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("TIMEOUT"))
        .andExpect(jsonPath("$.buildStatus").value("TIMEOUT"))
        .andReturn();

    String execId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
    ExecutionRun run = executions.findById(UUID.fromString(execId)).orElseThrow();
    assertThat(run.getStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
  }

  @Test
  void nonOwnerCannotExecute() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    Fixture fix = createAppliedProject(ownerToken, "Owner.java", "class Owner {}", true);

    mockMvc.perform(post("/api/v1/projects/" + fix.project.getId() + "/execute")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    // Also list
    mockMvc.perform(get("/api/v1/projects/" + fix.project.getId() + "/executions")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void cleanupOnFailurePreservesProjectAndNoLeak() throws Exception {
    String token = access(register());
    Fixture fix = createAppliedProject(token, "Keep.java", "class Keep {}", true);
    Path projectDir = storage.projectDir(fix.project.getId());
    String before = Files.readString(projectDir.resolve("src/Keep.java"));

    when(sandboxRunner.executeBuild(any(Path.class))).thenThrow(new SandboxRunner.SandboxException("docker boom"));

    mockMvc.perform(post("/api/v1/projects/" + fix.project.getId() + "/execute")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isInternalServerError());

    String after = Files.readString(projectDir.resolve("src/Keep.java"));
    assertThat(after).isEqualTo(before);
    // No leaked execution with SUCCESS
    assertThat(executions.findByProjectIdOrderByCreatedAtDesc(fix.project.getId()).stream()
        .anyMatch(r -> r.getStatus() == ExecutionStatus.SUCCESS)).isFalse();
  }

  @Test
  void unauthenticatedIs401() throws Exception {
    String token = access(register());
    Fixture fix = createAppliedProject(token, "Auth.java", "class Auth {}", true);
    mockMvc.perform(post("/api/v1/projects/" + fix.project.getId() + "/execute"))
        .andExpect(status().isUnauthorized());
  }

  // Helpers
  private record Fixture(Project project, String patchId) {}

  private Fixture createAppliedProject(String token, String fileName, String content, boolean withPatch) throws Exception {
    // Create project via API? For speed, create directly via repos and storage
    // Need to find owner from token
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    var owner = users.findByEmail(email).orElseThrow();

    Project project = projects.save(new Project(owner, "exec-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    lastProject = project;
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.HIGH, FindingSource.DETERMINISTIC, "test");
    finding.setFilePath("src/" + fileName);
    findings.save(finding);
    FixRequest fr = fixRequests.save(new FixRequest(finding, owner));
    fr.setStatus(com.verireview.fix.FixRequestStatus.IN_PROGRESS);
    fixRequests.save(fr);
    Patch patch = new Patch(fr, "diff --git a/src/" + fileName + " b/src/" + fileName + "\n--- a/src/" + fileName + "\n+++ b/src/" + fileName + "\n@@ -1 +1 @@\n- old\n+ new");
    patch.setProject(project);
    patch.setStatus(PatchStatus.APPLIED);
    patch.setFilesChanged(1);
    patches.save(patch);

    // Create file on disk
    Path projectDir = storage.projectDir(project.getId());
    Files.createDirectories(projectDir.resolve("src"));
    Files.writeString(projectDir.resolve("src/" + fileName), content, StandardCharsets.UTF_8);
    project.setStorageRef(projectDir.toString());
    projects.save(project);

    // Also create minimal pom.xml for real execution if not mocked - but we mock sandbox, so not needed
    // Create a simple pom to allow real execution if needed
    String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>test</artifactId><version>1.0</version></project>";
    Files.writeString(projectDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);

    return new Fixture(project, patch.getId().toString());
  }

  private String register() throws Exception {
    String email = "exec-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Exec\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String body) throws Exception {
    return objects.readTree(body).get("accessToken").asText();
  }
}
