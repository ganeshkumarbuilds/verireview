package com.verireview.check;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Verification gate result for a VERIFY check run.
 * Each gate is evaluated independently by the backend (never AI).
 */
@Entity
@Table(
    name = "check_run_gates",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_check_run_gates_run_name",
        columnNames = {"check_run_id", "gate_name"}
    )
)
public class CheckRunGate extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "check_run_id", nullable = false)
  private CheckRun checkRun;

  @Column(name = "gate_name", nullable = false, length = 100)
  private String gateName;

  @Column(name = "gate_description", length = 500)
  private String gateDescription;

  @Column(name = "passed", nullable = false)
  private boolean passed = false;

  @Column(name = "evidence", columnDefinition = "TEXT")
  private String evidence;

  @Column(name = "details", columnDefinition = "TEXT")
  private String details;

  public CheckRunGate() {
  }

  public CheckRunGate(CheckRun checkRun, String gateName, String gateDescription) {
    this.checkRun = checkRun;
    this.gateName = gateName;
    this.gateDescription = gateDescription;
  }

  public CheckRun getCheckRun() {
    return checkRun;
  }

  public void setCheckRun(CheckRun checkRun) {
    this.checkRun = checkRun;
  }

  public String getGateName() {
    return gateName;
  }

  public void setGateName(String gateName) {
    this.gateName = gateName;
  }

  public String getGateDescription() {
    return gateDescription;
  }

  public void setGateDescription(String gateDescription) {
    this.gateDescription = gateDescription;
  }

  public boolean isPassed() {
    return passed;
  }

  public void setPassed(boolean passed) {
    this.passed = passed;
  }

  public String getEvidence() {
    return evidence;
  }

  public void setEvidence(String evidence) {
    this.evidence = evidence;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }
}