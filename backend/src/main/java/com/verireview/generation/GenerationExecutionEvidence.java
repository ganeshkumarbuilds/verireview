package com.verireview.generation;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Build/test evidence from sandboxed execution of a generation iteration.
 * Linked to generation + iteration; NOT to a project (materialization happens later).
 * Persisted facts only — no secrets, no source code.
 */
@Entity
@Table(name = "generation_execution_evidence",
    uniqueConstraints = @UniqueConstraint(name = "uq_gen_exec_evidence_generation_iteration",
        columnNames = {"generation_id", "iteration"}))
public class GenerationExecutionEvidence extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @Column(name = "iteration", nullable = false)
  private int iteration;

  @Column(name = "command", nullable = false, columnDefinition = "TEXT")
  private String command;

  @Column(name = "exit_code", nullable = false)
  private int exitCode;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "stdout", columnDefinition = "TEXT")
  private String stdout;

  @Column(name = "stderr", columnDefinition = "TEXT")
  private String stderr;

  @Enumerated(EnumType.STRING)
  @Column(name = "build_status", nullable = false, length = 20)
  private BuildStatus buildStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "test_status", nullable = false, length = 20)
  private TestStatus testStatus;

  @Column(name = "failure_reason", length = 500)
  private String failureReason;

  public GenerationExecutionEvidence() {
  }

  public GenerationExecutionEvidence(Generation generation, int iteration, String command,
      int exitCode, long durationMs, String stdout, String stderr,
      BuildStatus buildStatus, TestStatus testStatus, String failureReason) {
    this.generation = generation;
    this.iteration = iteration;
    this.command = command;
    this.exitCode = exitCode;
    this.durationMs = durationMs;
    this.stdout = stdout;
    this.stderr = stderr;
    this.buildStatus = buildStatus;
    this.testStatus = testStatus;
    this.failureReason = failureReason;
  }

  public Generation getGeneration() {
    return generation;
  }

  public void setGeneration(Generation generation) {
    this.generation = generation;
  }

  public int getIteration() {
    return iteration;
  }

  public void setIteration(int iteration) {
    this.iteration = iteration;
  }

  public String getCommand() {
    return command;
  }

  public void setCommand(String command) {
    this.command = command;
  }

  public int getExitCode() {
    return exitCode;
  }

  public void setExitCode(int exitCode) {
    this.exitCode = exitCode;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }

  public String getStdout() {
    return stdout;
  }

  public void setStdout(String stdout) {
    this.stdout = stdout;
  }

  public String getStderr() {
    return stderr;
  }

  public void setStderr(String stderr) {
    this.stderr = stderr;
  }

  public BuildStatus getBuildStatus() {
    return buildStatus;
  }

  public void setBuildStatus(BuildStatus buildStatus) {
    this.buildStatus = buildStatus;
  }

  public TestStatus getTestStatus() {
    return testStatus;
  }

  public void setTestStatus(TestStatus testStatus) {
    this.testStatus = testStatus;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public enum BuildStatus {
    PENDING, RUNNING, SUCCESS, FAILURE, TIMEOUT
  }

  public enum TestStatus {
    PENDING, RUNNING, SUCCESS, FAILURE, TIMEOUT, NOT_APPLICABLE
  }
}