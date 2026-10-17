package com.verireview.check;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verireview.fix.FixRequest;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.FixRequestStatus;
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
import com.verireview.user.dto.RegisterRequest;
import com.verireview.user.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dashboard totals are owner-scoped and derived from real rows: project file
 * counts (only for projects that have a completed REVIEW/VERIFY run), OPEN
 * findings by severity, and COMPLETED fix requests. Backed by a real
 * PostgreSQL (Testcontainers) and exercised through MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardSummaryTotalsTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private UserRepository users;

  @Autowired
  private ProjectRepository projects;

  @Autowired
  private ProjectFileRepository projectFiles;

  @Autowired
  private ReviewRepository reviews;

  @Autowired
  private FindingRepository findings;

  @Autowired
  private FixRequestRepository fixRequests;

  @Autowired
  private CheckRunRepository checkRuns;

  @Test
  void newUserGetsZeros() throws Exception {
    String token = register("dash-empty@example.com");

    mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalFilesVerified").value(0))
        .andExpect(jsonPath("$.totalIssuesFound").value(0))
        .andExpect(jsonPath("$.totalIssuesFixed").value(0))
        .andExpect(jsonPath("$.projects").isArray())
        .andExpect(jsonPath("$.projects").isEmpty());
  }

  @Test
  void userWithDataGetsCorrectScopedTotals() throws Exception {
    String email = "dash-data@example.com";
    String token = register(email);
    User owner = users.findByEmail(email).orElseThrow();

    Project project = projects.save(new Project(owner, "billing", ProjectSourceType.ZIP_UPLOAD));

    // Three files -> totalFilesVerified = 3.
    for (int i = 0; i < 3; i++) {
      ProjectFile file = new ProjectFile(project, "src/File" + i + ".java", 10L + i,
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
      file.setContent("class File" + i + " {}");
      projectFiles.save(file);
    }

    Review review = reviews.save(new Review(project));
    review.setStatus(ReviewStatus.COMPLETED);
    reviews.save(review);

    // Three OPEN findings -> totalIssuesFound = 3.
    Finding critical = findings.save(new Finding(review, FindingCategory.SECURITY,
        FindingSeverity.CRITICAL, FindingSource.DETERMINISTIC, "Hardcoded secret"));
    findings.save(new Finding(review, FindingCategory.BUG,
        FindingSeverity.HIGH, FindingSource.DETERMINISTIC, "Null deref"));

    // One COMPLETED fix request counts; one REQUESTED must not.
    FixRequest fixed = new FixRequest(critical, owner);
    fixed.setStatus(FixRequestStatus.COMPLETED);
    fixRequests.save(fixed);

    Finding other = findings.save(new Finding(review, FindingCategory.CODE_QUALITY,
        FindingSeverity.LOW, FindingSource.AI, "Long method"));
    FixRequest requested = new FixRequest(other, owner);
    requested.setStatus(FixRequestStatus.REQUESTED);
    fixRequests.save(requested);

    mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalFilesVerified").value(3))
        .andExpect(jsonPath("$.totalIssuesFound").value(3))
        .andExpect(jsonPath("$.totalIssuesFixed").value(1))
        .andExpect(jsonPath("$.projects.length()").value(1))
        .andExpect(jsonPath("$.projects[0].name").value("billing"))
        .andExpect(jsonPath("$.projects[0].filesVerified").value(3))
        .andExpect(jsonPath("$.projects[0].issuesFound").value(3))
        .andExpect(jsonPath("$.projects[0].issuesFixed").value(1));
  }

  @Test
  void uploadedButUnreviewedProjectReportsNoVerifiedFiles() throws Exception {
    String email = "dash-unreviewed@example.com";
    String token = register(email);
    User owner = users.findByEmail(email).orElseThrow();

    Project project = projects.save(new Project(owner, "scratch", ProjectSourceType.ZIP_UPLOAD));
    for (int i = 0; i < 4; i++) {
      ProjectFile file = new ProjectFile(project, "src/File" + i + ".java", 10L + i,
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
      file.setContent("class File" + i + " {}");
      projectFiles.save(file);
    }

    // A QUEUED review is not a completed run: nothing has been verified yet.
    reviews.save(new Review(project));

    mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalFilesVerified").value(0))
        .andExpect(jsonPath("$.projects.length()").value(1))
        .andExpect(jsonPath("$.projects[0].filesVerified").value(0));
  }

  @Test
  void succeededVerifyCheckRunCountsFilesAsVerified() throws Exception {
    String email = "dash-verify@example.com";
    String token = register(email);
    User owner = users.findByEmail(email).orElseThrow();

    Project project = projects.save(new Project(owner, "verified", ProjectSourceType.ZIP_UPLOAD));
    for (int i = 0; i < 2; i++) {
      ProjectFile file = new ProjectFile(project, "src/File" + i + ".java", 10L + i,
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
      file.setContent("class File" + i + " {}");
      projectFiles.save(file);
    }

    CheckRun verifyRun = new CheckRun(project, CheckFeature.VERIFY);
    verifyRun.setStatus(CheckStatus.SUCCEEDED);
    checkRuns.save(verifyRun);

    mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalFilesVerified").value(2))
        .andExpect(jsonPath("$.projects[0].filesVerified").value(2));
  }

  private String register(String email) throws Exception {
    RegisterRequest request = new RegisterRequest(email, "password123", "Dash User");
    String response = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readValue(response, TokenResponse.class).accessToken();
  }
}
