package com.verireview.check;

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
import java.time.Instant;
import java.util.UUID;

/**
 * Ordered step within a check run. Each step has its own status, progress,
 * duration, and log tail for real-time visibility.
 */
@Entity
@Table(
    name = "check_steps",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_check_steps_run_order",
        columnNames = {"check_run_id", "step_order"}
    )
)
public class CheckStep extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "check_run_id", nullable = false)
  private CheckRun checkRun;

  @Column(name = "step_order", nullable = false)
  private int stepOrder;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private CheckStatus status = CheckStatus.PENDING;

  @Column(name = "progress", nullable = false)
  private int progress = 0;

  @Column(name = "current_message", length = 500)
  private String currentMessage;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "log_tail", columnDefinition = "TEXT")
  private String logTail;

  public CheckStep() {
  }

  public CheckStep(CheckRun checkRun, int stepOrder, String name) {
    this.checkRun = checkRun;
    this.stepOrder = stepOrder;
    this.name = name;
  }

  public CheckRun getCheckRun() {
    return checkRun;
  }

  public void setCheckRun(CheckRun checkRun) {
    this.checkRun = checkRun;
  }

  public int getStepOrder() {
    return stepOrder;
  }

  public void setStepOrder(int stepOrder) {
    this.stepOrder = stepOrder;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public CheckStatus getStatus() {
    return status;
  }

  public void setStatus(CheckStatus status) {
    this.status = status;
  }

  public int getProgress() {
    return progress;
  }

  public void setProgress(int progress) {
    this.progress = progress;
  }

  public String getCurrentMessage() {
    return currentMessage;
  }

  public void setCurrentMessage(String currentMessage) {
    this.currentMessage = currentMessage;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getLogTail() {
    return logTail;
  }

  public void setLogTail(String logTail) {
    this.logTail = logTail;
  }
}