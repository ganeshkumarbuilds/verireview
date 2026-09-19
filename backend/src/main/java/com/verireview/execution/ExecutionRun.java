package com.verireview.execution;

import com.verireview.common.BaseEntity;
import com.verireview.fix.Patch;
import com.verireview.project.Project;
import com.verireview.verification.BuildStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Sandbox execution evidence for an APPLIED project. Never runs on host.
 */
@Entity
@Table(name = "execution_runs")
public class ExecutionRun extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "patch_id")
  private Patch patch;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ExecutionStatus status = ExecutionStatus.PENDING;

  @Column(name = "exit_code")
  private Integer exitCode;

  @Column(name = "stdout", columnDefinition = "TEXT")
  private String stdout;

  @Column(name = "stderr", columnDefinition = "TEXT")
  private String stderr;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Enumerated(EnumType.STRING)
  @Column(name = "build_status", length = 20)
  private BuildStatus buildStatus = BuildStatus.PENDING;

  public ExecutionRun() {}

  public ExecutionRun(Project project, Patch patch) {
    this.project = project;
    this.patch = patch;
  }

  public Project getProject() { return project; }
  public void setProject(Project project) { this.project = project; }

  public Patch getPatch() { return patch; }
  public void setPatch(Patch patch) { this.patch = patch; }

  public ExecutionStatus getStatus() { return status; }
  public void setStatus(ExecutionStatus status) { this.status = status; }

  public Integer getExitCode() { return exitCode; }
  public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

  public String getStdout() { return stdout; }
  public void setStdout(String stdout) { this.stdout = stdout; }

  public String getStderr() { return stderr; }
  public void setStderr(String stderr) { this.stderr = stderr; }

  public Long getDurationMs() { return durationMs; }
  public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

  public BuildStatus getBuildStatus() { return buildStatus; }
  public void setBuildStatus(BuildStatus buildStatus) { this.buildStatus = buildStatus; }
}
