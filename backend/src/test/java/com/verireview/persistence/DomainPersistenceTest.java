package com.verireview.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.verireview.agent.AgentExecution;
import com.verireview.agent.AgentExecutionRepository;
import com.verireview.agent.AgentExecutionStatus;
import com.verireview.agent.AgentType;
import com.verireview.audit.AuditLog;
import com.verireview.audit.AuditLogRepository;
import com.verireview.fix.FixRequest;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.Patch;
import com.verireview.fix.PatchRepository;
import com.verireview.project.GitRepository;
import com.verireview.project.GitRepositoryRepository;
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
import com.verireview.review.FindingStatus;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.review.ReviewStatus;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import com.verireview.verification.TestResult;
import com.verireview.verification.TestResultRepository;
import com.verireview.verification.TestStatus;
import com.verireview.verification.VerificationRun;
import com.verireview.verification.VerificationRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the full design chain (user → project → review → finding →
 * fix → patch → run → test result, plus repo/file/agent/audit rows) and
 * proves relationships and JSONB round-trip against real PostgreSQL.
 *
 * A Patch is project-owned in this legacy/project workflow. Generation
 * patches use generation_id instead; the database requires exactly one owner.
 */
@Transactional
class DomainPersistenceTest extends AbstractPersistenceTest {

  @Autowired
  private UserRepository users;

  @Autowired
  private ProjectRepository projects;

  @Autowired
  private GitRepositoryRepository gitRepositories;

  @Autowired
  private ProjectFileRepository projectFiles;

  @Autowired
  private ReviewRepository reviews;

  @Autowired
  private FindingRepository findings;

  @Autowired
  private FixRequestRepository fixRequests;

  @Autowired
  private PatchRepository patches;

  @Autowired
  private VerificationRunRepository runs;

  @Autowired
  private TestResultRepository testResults;

  @Autowired
  private AgentExecutionRepository agentExecutions;

  @Autowired
  private AuditLogRepository auditLogs;

  @PersistenceContext
  private EntityManager entities;

  @Test
  void fullChainPersistsAndRelationshipsResolve() {
    User owner = users.save(new User("dev-" + UUID.randomUUID() + "@example.com", "hash"));
    Project project = projects.save(new Project(owner, "demo", ProjectSourceType.GITHUB));
    GitRepository repo = gitRepositories.save(new GitRepository(project, "https://github.com/o/r"));

    ProjectFile file = new ProjectFile(project, "src/Main.java", 42L,
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    file.setContent("class Main {}");
    file.setLanguage("java");
    projectFiles.save(file);

    Review review = reviews.save(new Review(project));
    review.setStatus(ReviewStatus.COMPLETED);
    Finding finding = new Finding(review, FindingCategory.SECURITY,
        FindingSeverity.HIGH, FindingSource.DETERMINISTIC, "SQL concat");
    finding.setEvidence("{\"rule\":\"SpotBugs:SQL_INJECTION\"}");
    finding.setFilePath("src/Main.java");
    findings.save(finding);

    FixRequest fix = fixRequests.save(new FixRequest(finding, owner));

    Patch patch = new Patch(fix, "--- a\n+++ b\n");
    // V15 requires every Patch to have exactly one owner. This is the
    // project-owned path exercised by this persistence test.
    patch.setProject(project);
    patch = patches.save(patch);

    VerificationRun run = runs.save(new VerificationRun(patch));
    TestResult result = testResults.save(
        new TestResult(run, "Suite", "testX", TestStatus.PASSED));

    AgentExecution execution = new AgentExecution(project, AgentType.REVIEW,
        "model", "v1", "ab".repeat(32));
    execution.setReview(review);
    agentExecutions.save(execution);

    AuditLog audit = new AuditLog("REVIEW_COMPLETED", "review", review.getId().toString());
    audit.setActor(owner);
    audit.setMetadata("{\"findings\":1}");
    auditLogs.save(audit);

    entities.flush();
    entities.clear();

    Finding reloaded = findings.findById(finding.getId()).orElseThrow();
    assertThat(reloaded.getReview().getId()).isEqualTo(review.getId());
    assertThat(reloaded.getReview().getProject().getId()).isEqualTo(project.getId());
    assertThat(reloaded.getReview().getProject().getOwner().getId()).isEqualTo(owner.getId());
    assertThat(reloaded.getEvidence().replace(" ", ""))
        .isEqualTo("{\"rule\":\"SpotBugs:SQL_INJECTION\"}");
    assertThat(reloaded.getStatus()).isEqualTo(FindingStatus.OPEN);

    VerificationRun reloadedRun = runs.findById(run.getId()).orElseThrow();
    assertThat(reloadedRun.getPatch().getFixRequest().getFinding().getId())
        .isEqualTo(finding.getId());

    assertThat(testResults.findById(result.getId())).isPresent();
    assertThat(gitRepositories.findById(repo.getId()).orElseThrow()
        .getProject().getId()).isEqualTo(project.getId());
    assertThat(auditLogs.findById(audit.getId()).orElseThrow()
        .getActor().getId()).isEqualTo(owner.getId());
    assertThat(owner.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
  }
}
