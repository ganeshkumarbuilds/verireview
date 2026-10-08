package com.verireview.check;

import com.verireview.common.BaseEntity;
import com.verireview.generation.Generation;
import com.verireview.project.Project;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Unified check run for all four features: GENERATE, REVIEW, FIX, VERIFY.
 * One row per feature execution per project (or generation).
 */
@Entity
@Table(
    name = "check_runs",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_check_runs_project_feature_latest",
        columnNames = {"project_id", "feature", "created_at"}
    )
)
public class CheckRun extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "generation_id")
  private Generation generation;

  @Enumerated(EnumType.STRING)
  @Column(name = "feature", nullable = false, length = 20)
  private CheckFeature feature;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private CheckStatus status = CheckStatus.QUEUED;

  @Column(name = "progress", nullable = false)
  private int progress = 0;

  @Column(name = "current_step", length = 500)
  private String currentStep;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "severity_critical", nullable = false)
  private int severityCritical = 0;

  @Column(name = "severity_high", nullable = false)
  private int severityHigh = 0;

  @Column(name = "severity_medium", nullable = false)
  private int severityMedium = 0;

  @Column(name = "severity_low", nullable = false)
  private int severityLow = 0;

  @Column(name = "severity_info", nullable = false)
  private int severityInfo = 0;

  public CheckRun() {
  }

  public CheckRun(Project project, CheckFeature feature) {
    this.project = project;
    this.feature = feature;
  }

  public CheckRun(Project project, Generation generation, CheckFeature feature) {
    this.project = project;
    this.generation = generation;
    this.feature = feature;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public Generation getGeneration() {
    return generation;
  }

  public void setGeneration(Generation generation) {
    this.generation = generation;
  }

  public CheckFeature getFeature() {
    return feature;
  }

  public void setFeature(CheckFeature feature) {
    this.feature = feature;
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

  public String getCurrentStep() {
    return currentStep;
  }

  public void setCurrentStep(String currentStep) {
    this.currentStep = currentStep;
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

  public int getSeverityCritical() {
    return severityCritical;
  }

  public void setSeverityCritical(int severityCritical) {
    this.severityCritical = severityCritical;
  }

  public int getSeverityHigh() {
    return severityHigh;
  }

  public void setSeverityHigh(int severityHigh) {
    this.severityHigh = severityHigh;
  }

  public int getSeverityMedium() {
    return severityMedium;
  }

  public void setSeverityMedium(int severityMedium) {
    this.severityMedium = severityMedium;
  }

  public int getSeverityLow() {
    return severityLow;
  }

  public void setSeverityLow(int severityLow) {
    this.severityLow = severityLow;
  }

  public int getSeverityInfo() {
    return severityInfo;
  }

  public void setSeverityInfo(int severityInfo) {
    this.severityInfo = severityInfo;
  }

  public int getTotalSeverityCount() {
    return severityCritical + severityHigh + severityMedium + severityLow + severityInfo;
  }
}