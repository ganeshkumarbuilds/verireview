package com.verireview.project;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * GitHub import metadata. At most one row per project (unique FK); kept
 * separate from {@code Project} because it has a distinct lifecycle and
 * allows future multi-repo projects without touching the project row.
 */
@Entity
@Table(name = "repositories")
public class GitRepository extends BaseEntity {

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false, unique = true)
  private Project project;

  @Column(name = "repo_url", nullable = false, length = 2000)
  private String repoUrl;

  @Column(name = "branch", length = 200)
  private String branch;

  @Column(name = "commit_sha", length = 64)
  private String commitSha;

  @Column(name = "imported_at")
  private Instant importedAt;

  public GitRepository() {
  }

  public GitRepository(Project project, String repoUrl) {
    this.project = project;
    this.repoUrl = repoUrl;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public String getRepoUrl() {
    return repoUrl;
  }

  public void setRepoUrl(String repoUrl) {
    this.repoUrl = repoUrl;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public String getCommitSha() {
    return commitSha;
  }

  public void setCommitSha(String commitSha) {
    this.commitSha = commitSha;
  }

  public Instant getImportedAt() {
    return importedAt;
  }

  public void setImportedAt(Instant importedAt) {
    this.importedAt = importedAt;
  }
}
