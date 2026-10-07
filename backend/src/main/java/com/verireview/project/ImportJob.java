package com.verireview.project;

import com.verireview.common.BaseEntity;
import com.verireview.user.User;
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

/**
 * Async import job tracking for ZIP and GitHub imports.
 * Status transitions: QUEUED → EXTRACTING → INDEXING → DONE/FAILED
 */
@Entity
@Table(
    name = "import_jobs",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_import_jobs_owner_created", columnNames = {"owner_id", "created_at"}))
public class ImportJob extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id")
  private Project project;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private ProjectSourceType sourceType;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "language", length = 100)
  private String language;

  @Column(name = "github_url", length = 500)
  private String githubUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ImportJobStatus status = ImportJobStatus.QUEUED;

  @Column(name = "files_processed", nullable = false)
  private long filesProcessed = 0;

  @Column(name = "files_total", nullable = false)
  private long filesTotal = 0;

  @Column(name = "bytes_processed", nullable = false)
  private long bytesProcessed = 0;

  @Column(name = "bytes_total", nullable = false)
  private long bytesTotal = 0;

  @Column(name = "current_step", length = 500)
  private String currentStep;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "staged_file_path", length = 1000)
  private String stagedFilePath;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  public ImportJob() {
  }

  public ImportJob(User owner, ProjectSourceType sourceType, String name) {
    this.owner = owner;
    this.sourceType = sourceType;
    this.name = name;
  }

  public User getOwner() {
    return owner;
  }

  public void setOwner(User owner) {
    this.owner = owner;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public ProjectSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(ProjectSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getGithubUrl() {
    return githubUrl;
  }

  public void setGithubUrl(String githubUrl) {
    this.githubUrl = githubUrl;
  }

  public ImportJobStatus getStatus() {
    return status;
  }

  public void setStatus(ImportJobStatus status) {
    this.status = status;
  }

  public long getFilesProcessed() {
    return filesProcessed;
  }

  public void setFilesProcessed(long filesProcessed) {
    this.filesProcessed = filesProcessed;
  }

  public long getFilesTotal() {
    return filesTotal;
  }

  public void setFilesTotal(long filesTotal) {
    this.filesTotal = filesTotal;
  }

  public long getBytesProcessed() {
    return bytesProcessed;
  }

  public void setBytesProcessed(long bytesProcessed) {
    this.bytesProcessed = bytesProcessed;
  }

  public long getBytesTotal() {
    return bytesTotal;
  }

  public void setBytesTotal(long bytesTotal) {
    this.bytesTotal = bytesTotal;
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

  public String getStagedFilePath() {
    return stagedFilePath;
  }

  public void setStagedFilePath(String stagedFilePath) {
    this.stagedFilePath = stagedFilePath;
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
}