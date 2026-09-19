package com.verireview.review;

import com.verireview.common.BaseEntity;
import com.verireview.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One review execution over a project. Groups findings and review-agent runs.
 */
@Entity
@Table(name = "reviews")
public class Review extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ReviewStatus status = ReviewStatus.QUEUED;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "finding_count", nullable = false)
  private int findingCount;

  @Column(name = "error", columnDefinition = "TEXT")
  private String error;

  public Review() {
  }

  public Review(Project project) {
    this.project = project;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public ReviewStatus getStatus() {
    return status;
  }

  public void setStatus(ReviewStatus status) {
    this.status = status;
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

  public int getFindingCount() {
    return findingCount;
  }

  public void setFindingCount(int findingCount) {
    this.findingCount = findingCount;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
