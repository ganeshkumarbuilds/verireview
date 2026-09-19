package com.verireview.review;

import com.verireview.common.BaseEntity;
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
 * Structured issue found by deterministic tools or the Review Agent.
 * {@code evidence} is schemaless tool/agent payload stored as JSONB.
 */
@Entity
@Table(name = "findings")
public class Finding extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "review_id", nullable = false)
  private Review review;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 20)
  private FindingCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 20)
  private FindingSeverity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 20)
  private FindingSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private FindingStatus status = FindingStatus.OPEN;

  @Column(name = "title", nullable = false, length = 500)
  private String title;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "file_path", length = 1000)
  private String filePath;

  @Column(name = "line_start")
  private Integer lineStart;

  @Column(name = "line_end")
  private Integer lineEnd;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence", columnDefinition = "jsonb")
  private String evidence;

  @Column(name = "dedup_key", length = 128)
  private String dedupKey;

  public Finding() {
  }

  public Finding(Review review, FindingCategory category, FindingSeverity severity,
      FindingSource source, String title) {
    this.review = review;
    this.category = category;
    this.severity = severity;
    this.source = source;
    this.title = title;
  }

  public Review getReview() {
    return review;
  }

  public void setReview(Review review) {
    this.review = review;
  }

  public FindingCategory getCategory() {
    return category;
  }

  public void setCategory(FindingCategory category) {
    this.category = category;
  }

  public FindingSeverity getSeverity() {
    return severity;
  }

  public void setSeverity(FindingSeverity severity) {
    this.severity = severity;
  }

  public FindingSource getSource() {
    return source;
  }

  public void setSource(FindingSource source) {
    this.source = source;
  }

  public FindingStatus getStatus() {
    return status;
  }

  public void setStatus(FindingStatus status) {
    this.status = status;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public Integer getLineStart() {
    return lineStart;
  }

  public void setLineStart(Integer lineStart) {
    this.lineStart = lineStart;
  }

  public Integer getLineEnd() {
    return lineEnd;
  }

  public void setLineEnd(Integer lineEnd) {
    this.lineEnd = lineEnd;
  }

  public String getEvidence() {
    return evidence;
  }

  public void setEvidence(String evidence) {
    this.evidence = evidence;
  }

  public String getDedupKey() {
    return dedupKey;
  }

  public void setDedupKey(String dedupKey) {
    this.dedupKey = dedupKey;
  }
}
