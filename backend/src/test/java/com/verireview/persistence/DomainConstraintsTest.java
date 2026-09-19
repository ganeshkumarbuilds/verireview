package com.verireview.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verireview.fix.FixRequest;
import com.verireview.fix.FixRequestRepository;
import com.verireview.fix.FixRequestStatus;
import com.verireview.fix.PatchRepository;
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
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import com.verireview.verification.VerificationRun;
import com.verireview.verification.VerificationRunRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the database — not application code — enforces the Phase 2
 * invariants: uniqueness, foreign keys, the one-open-fix gate, the
 * file-body XOR check, and the mandatory verification-run patch link.
 */
class DomainConstraintsTest extends AbstractPersistenceTest {

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
  private PatchRepository patches;

  @Autowired
  private VerificationRunRepository runs;

  @Autowired
  private JdbcTemplate jdbc;

  private User newUser() {
    return users.saveAndFlush(
        new User("u-" + UUID.randomUUID() + "@example.com", "hash"));
  }

  private Project newProject(User owner, String name) {
    return projects.saveAndFlush(new Project(owner, name, ProjectSourceType.PASTE));
  }

  private Finding newFinding(Project project) {
    Review review = reviews.saveAndFlush(new Review(project));
    return findings.saveAndFlush(new Finding(review, FindingCategory.BUG,
        FindingSeverity.LOW, FindingSource.AI, "title"));
  }

  @Test
  void duplicateEmailRejected() {
    User first = newUser();
    User twin = new User(first.getEmail(), "other-hash");
    assertThatThrownBy(() -> users.saveAndFlush(twin))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void duplicateProjectNamePerOwnerRejected() {
    User owner = newUser();
    newProject(owner, "same-name");
    assertThatThrownBy(() -> newProject(owner, "same-name"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void duplicateProjectPathRejected() {
    Project project = newProject(newUser(), "p");
    ProjectFile first = new ProjectFile(project, "a.java", 1L, "0".repeat(64));
    first.setContent("x");
    projectFiles.saveAndFlush(first);
    ProjectFile twin = new ProjectFile(project, "a.java", 2L, "1".repeat(64));
    twin.setContent("y");
    assertThatThrownBy(() -> projectFiles.saveAndFlush(twin))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void projectFileBodyRequiresExactlyOneOfContentOrRef() {
    Project project = newProject(newUser(), "p");
    ProjectFile neither = new ProjectFile(project, "n.java", 1L, "0".repeat(64));
    assertThatThrownBy(() -> projectFiles.saveAndFlush(neither))
        .isInstanceOf(DataIntegrityViolationException.class);
    ProjectFile both = new ProjectFile(project, "b.java", 1L, "1".repeat(64));
    both.setContent("x");
    both.setContentRef("s3://bucket/key");
    assertThatThrownBy(() -> projectFiles.saveAndFlush(both))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void secondOpenFixRequestForSameFindingRejected() {
    User owner = newUser();
    Finding finding = newFinding(newProject(owner, "p"));
    fixRequests.saveAndFlush(new FixRequest(finding, owner));
    assertThatThrownBy(() -> fixRequests.saveAndFlush(new FixRequest(finding, owner)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void newFixRequestAllowedAfterPreviousOneClosed() {
    User owner = newUser();
    Finding finding = newFinding(newProject(owner, "p"));
    FixRequest first = fixRequests.saveAndFlush(new FixRequest(finding, owner));
    first.setStatus(FixRequestStatus.COMPLETED);
    fixRequests.saveAndFlush(first);
    fixRequests.saveAndFlush(new FixRequest(finding, owner));
  }

  @Test
  void verificationRunRequiresPatchLink() {
    VerificationRun orphan = new VerificationRun();
    assertThatThrownBy(() -> runs.saveAndFlush(orphan))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void projectWithUnknownOwnerRejectedByForeignKey() {
    assertThatThrownBy(() -> jdbc.update(
        "INSERT INTO projects (owner_id, name, source_type, status) VALUES (?, 'x', 'PASTE', 'ACTIVE')",
        UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
  }
}
