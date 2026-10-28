package com.verireview.analysis;

import com.verireview.common.BaseEntity;
import com.verireview.review.Review;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "analysis_checks",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_analysis_checks_review_analyzer",
        columnNames = {"review_id", "analyzer"}))
public class AnalysisCheck extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "review_id", nullable = false)
  private Review review;

  @Column(name = "analyzer", nullable = false, length = 100)
  private String analyzer;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AnalysisCheckStatus status;

  @Column(name = "reason", columnDefinition = "TEXT")
  private String reason;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "files_analyzed", nullable = false)
  private int filesAnalyzed;

  @Column(name = "finding_count", nullable = false)
  private int findingCount;

  protected AnalysisCheck() {
  }

  public AnalysisCheck(
      Review review, String analyzer, AnalysisCheckStatus status, String reason,
      long durationMs, int filesAnalyzed, int findingCount) {
    this.review = review;
    this.analyzer = analyzer;
    this.status = status;
    this.reason = reason;
    this.durationMs = durationMs;
    this.filesAnalyzed = filesAnalyzed;
    this.findingCount = findingCount;
  }

  public Review getReview() { return review; }
  public String getAnalyzer() { return analyzer; }
  public AnalysisCheckStatus getStatus() { return status; }
  public String getReason() { return reason; }
  public Long getDurationMs() { return durationMs; }
  public int getFilesAnalyzed() { return filesAnalyzed; }
  public int getFindingCount() { return findingCount; }
}
