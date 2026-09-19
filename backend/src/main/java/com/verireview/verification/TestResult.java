package com.verireview.verification;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Per-test row of a verification run — the "which test proved it" evidence.
 */
@Entity
@Table(name = "test_results")
public class TestResult extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "verification_run_id", nullable = false)
  private VerificationRun verificationRun;

  @Column(name = "suite", nullable = false, length = 500)
  private String suite;

  @Column(name = "test_name", nullable = false, length = 500)
  private String testName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private TestStatus status;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "message", columnDefinition = "TEXT")
  private String message;

  public TestResult() {
  }

  public TestResult(VerificationRun verificationRun, String suite, String testName,
      TestStatus status) {
    this.verificationRun = verificationRun;
    this.suite = suite;
    this.testName = testName;
    this.status = status;
  }

  public VerificationRun getVerificationRun() {
    return verificationRun;
  }

  public void setVerificationRun(VerificationRun verificationRun) {
    this.verificationRun = verificationRun;
  }

  public String getSuite() {
    return suite;
  }

  public void setSuite(String suite) {
    this.suite = suite;
  }

  public String getTestName() {
    return testName;
  }

  public void setTestName(String testName) {
    this.testName = testName;
  }

  public TestStatus getStatus() {
    return status;
  }

  public void setStatus(TestStatus status) {
    this.status = status;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
