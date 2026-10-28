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
@Table(name = "analysis_file_results",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_analysis_file_results_review_path",
        columnNames = {"review_id", "path"}))
public class AnalysisFileResult extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "review_id", nullable = false)
  private Review review;

  @Column(name = "path", nullable = false, length = 1000)
  private String path;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AnalysisFileStatus status;

  @Column(name = "reason", columnDefinition = "TEXT")
  private String reason;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "finding_count", nullable = false)
  private int findingCount;

  protected AnalysisFileResult() {
  }

  public AnalysisFileResult(
      Review review, String path, AnalysisFileStatus status, String reason,
      long sizeBytes, int findingCount) {
    this.review = review;
    this.path = path;
    this.status = status;
    this.reason = reason;
    this.sizeBytes = sizeBytes;
    this.findingCount = findingCount;
  }

  public Review getReview() { return review; }
  public String getPath() { return path; }
  public AnalysisFileStatus getStatus() { return status; }
  public String getReason() { return reason; }
  public long getSizeBytes() { return sizeBytes; }
  public int getFindingCount() { return findingCount; }
}
