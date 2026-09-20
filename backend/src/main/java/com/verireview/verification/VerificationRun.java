package com.verireview.verification;

import com.verireview.common.BaseEntity;
import com.verireview.execution.ExecutionRun;
import com.verireview.fix.Patch;
import com.verireview.generation.Generation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Evidence backing every verdict: one isolated sandbox execution of a patch.
 * The mandatory patch link enforces "no verdict without evidence" at the
 * schema level; the VERIFIED policy itself is enforced in a later phase.
 */
@Entity
@Table(name = "verification_runs")
public class VerificationRun extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "patch_id", nullable = false)
  private Patch patch;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "execution_run_id")
  private ExecutionRun executionRun;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "generation_id")
  private Generation generation;

  @Enumerated(EnumType.STRING)
  @Column(name = "build_status", nullable = false, length = 20)
  private BuildStatus buildStatus = BuildStatus.PENDING;

  @Column(name = "tests_total", nullable = false)
  private int testsTotal;

  @Column(name = "tests_passed", nullable = false)
  private int testsPassed;

  @Column(name = "tests_failed", nullable = false)
  private int testsFailed;

  @Column(name = "tests_skipped", nullable = false)
  private int testsSkipped;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "static_delta", columnDefinition = "jsonb")
  private String staticDelta;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "security_delta", columnDefinition = "jsonb")
  private String securityDelta;

  @Enumerated(EnumType.STRING)
  @Column(name = "verdict", nullable = false, length = 20)
  private VerificationVerdict verdict = VerificationVerdict.PENDING;

  @Column(name = "log_ref", length = 1000)
  private String logRef;

  @Column(name = "duration_ms")
  private Long durationMs;

  public VerificationRun() {
  }

  public VerificationRun(Patch patch) {
    this.patch = patch;
  }

  public Patch getPatch() {
    return patch;
  }

  public void setPatch(Patch patch) {
    this.patch = patch;
  }

  public ExecutionRun getExecutionRun() {
    return executionRun;
  }

  public void setExecutionRun(ExecutionRun executionRun) {
    this.executionRun = executionRun;
  }

  public Generation getGeneration() {
    return generation;
  }

  public void setGeneration(Generation generation) {
    this.generation = generation;
  }

  public BuildStatus getBuildStatus() {
    return buildStatus;
  }

  public void setBuildStatus(BuildStatus buildStatus) {
    this.buildStatus = buildStatus;
  }

  public int getTestsTotal() {
    return testsTotal;
  }

  public void setTestsTotal(int testsTotal) {
    this.testsTotal = testsTotal;
  }

  public int getTestsPassed() {
    return testsPassed;
  }

  public void setTestsPassed(int testsPassed) {
    this.testsPassed = testsPassed;
  }

  public int getTestsFailed() {
    return testsFailed;
  }

  public void setTestsFailed(int testsFailed) {
    this.testsFailed = testsFailed;
  }

  public int getTestsSkipped() {
    return testsSkipped;
  }

  public void setTestsSkipped(int testsSkipped) {
    this.testsSkipped = testsSkipped;
  }

  public String getStaticDelta() {
    return staticDelta;
  }

  public void setStaticDelta(String staticDelta) {
    this.staticDelta = staticDelta;
  }

  public String getSecurityDelta() {
    return securityDelta;
  }

  public void setSecurityDelta(String securityDelta) {
    this.securityDelta = securityDelta;
  }

  public VerificationVerdict getVerdict() {
    return verdict;
  }

  public void setVerdict(VerificationVerdict verdict) {
    this.verdict = verdict;
  }

  public String getLogRef() {
    return logRef;
  }

  public void setLogRef(String logRef) {
    this.logRef = logRef;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }
}
