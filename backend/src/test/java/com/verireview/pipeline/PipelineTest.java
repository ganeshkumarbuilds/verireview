package com.verireview.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.check.CheckFeature;
import com.verireview.check.CheckRun;
import com.verireview.check.CheckRunService;
import com.verireview.execution.ExecutionRun;
import com.verireview.execution.ExecutionRunRepository;
import com.verireview.execution.ExecutionStatus;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline read model (find -> fix -> verify visibility). Proves the endpoint
 * returns real, derived stage states and never 500s for a project with no data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PipelineTest extends AbstractPersistenceTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objects;
  @Autowired UserRepository users;
  @Autowired ProjectRepository projects;
  @Autowired ProjectFileRepository projectFiles;
  @Autowired ReviewRepository reviews;
  @Autowired FindingRepository findings;
  @Autowired CheckRunService checkRunService;
  @Autowired ExecutionRunRepository executions;

  @Test
  void emptyProjectReturnsFiveWaitingStagesNoServerError() throws Exception {
    String token = access(register());
    Project project = projects.save(
        new Project(tokenOwner(token), "pipe-empty-" + UUID.randomUUID(), ProjectSourceType.PASTE));

    MvcResult result = mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/pipeline")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
        .andExpect(jsonPath("$.stages.length()").value(5))
        .andExpect(jsonPath("$.stages[0].key").value("import"))
        .andExpect(jsonPath("$.stages[1].key").value("analyze"))
        .andExpect(jsonPath("$.stages[2].key").value("review"))
        .andExpect(jsonPath("$.stages[3].key").value("fix"))
        .andExpect(jsonPath("$.stages[4].key").value("verify"))
        .andExpect(jsonPath("$.stages[3].state").value("WAITING"))
        .andExpect(jsonPath("$.stages[4].state").value("WAITING"))
        .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception");
  }

  @Test
  void populatedProjectReflectsRealStages() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Project project = projects.save(
        new Project(owner, "pipe-full-" + UUID.randomUUID(), ProjectSourceType.PASTE));

    ProjectFile file = new ProjectFile(project, "src/App.java", 10L, "a".repeat(64));
    file.setContent("class App {}");
    projectFiles.save(file);

    Review review = new Review(project);
    review.setStatus(ReviewStatus.COMPLETED);
    review = reviews.save(review);
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.CRITICAL,
        FindingSource.DETERMINISTIC, "SQL injection");
    finding.setFilePath("src/App.java");
    findings.save(finding);
    Finding high = new Finding(review, FindingCategory.BUG, FindingSeverity.HIGH,
        FindingSource.AI, "Null pointer");
    findings.save(high);

    // Real FIX check run: completed.
    CheckRun fixRun = checkRunService.createCheckRun(owner.getId(), project.getId(),
        CheckFeature.FIX, null,
        List.of(new CheckRunService.CheckStepDefinition(0, "Propose fix")));
    checkRunService.markRunning(fixRun.getId());
    checkRunService.markSucceeded(fixRun.getId(), "Fix applied");

    // Real VERIFY check run: sandbox unavailable -> SKIPPED, never green.
    CheckRun verifyRun = checkRunService.createCheckRun(owner.getId(), project.getId(),
        CheckFeature.VERIFY, null,
        List.of(new CheckRunService.CheckStepDefinition(0, "Build and tests")));
    checkRunService.markRunning(verifyRun.getId());
    checkRunService.markSkipped(verifyRun.getId(), "Not verified: sandbox unavailable");

    mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/pipeline")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stages[0].state").value("COMPLETED"))
        .andExpect(jsonPath("$.stages[1].state").value("COMPLETED"))
        .andExpect(jsonPath("$.stages[2].state").value("COMPLETED"))
        .andExpect(jsonPath("$.stages[2].detail").value(org.hamcrest.Matchers.containsString("2 finding(s)")))
        .andExpect(jsonPath("$.stages[2].detail").value(org.hamcrest.Matchers.containsString("1 critical")))
        .andExpect(jsonPath("$.stages[3].state").value("COMPLETED"))
        .andExpect(jsonPath("$.stages[4].state").value("SKIPPED"))
        .andExpect(jsonPath("$.stages[4].detail").value("Not verified: sandbox unavailable"));
  }

  @Test
  void sandboxSkippedExecutionIsNeverReportedAsCompleted() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    Project project = projects.save(
        new Project(owner, "pipe-skip-" + UUID.randomUUID(), ProjectSourceType.PASTE));

    // Real execution evidence: the sandbox was disabled, so nothing ran.
    ExecutionRun exec = new ExecutionRun(project, null);
    exec.setStatus(ExecutionStatus.SKIPPED);
    exec.setBuildStatus(com.verireview.verification.BuildStatus.SKIPPED);
    exec.setStdout("Docker sandbox is disabled in this environment; build/test execution was skipped");
    executions.save(exec);

    mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/pipeline")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stages[4].state").value("SKIPPED"))
        .andExpect(jsonPath("$.stages[4].detail").value("Not verified: sandbox unavailable"));
  }

  @Test
  void nonOwnerGets404WithCodesAndStatusInBody() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    Project project = projects.save(
        new Project(tokenOwner(ownerToken), "pipe-own-" + UUID.randomUUID(), ProjectSourceType.PASTE));

    mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/pipeline")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.status").value(404))
        .andExpect(jsonPath("$.error.code").exists())
        .andExpect(jsonPath("$.error.message").exists());
  }

  private User tokenOwner(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }

  private String register() throws Exception {
    String email = "pipe-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Pipe\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String body) throws Exception {
    return objects.readTree(body).get("accessToken").asText();
  }
}
