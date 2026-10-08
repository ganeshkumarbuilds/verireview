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
 * Unified schema: severity, category (bug/security/performance/maintainability/dependency/secret),
 * file, line range, rule, evidence snippet, explanation, suggested fix, confidence,
 * source (tool or AI), tool_confirmed (cross-checked against tool output).
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

  // Unified schema fields
  @Column(name = "rule", length = 200)
  private String rule;

  @Column(name = "evidence_snippet", columnDefinition = "TEXT")
  private String evidenceSnippet;

  @Column(name = "explanation", columnDefinition = "TEXT")
  private String explanation;

  @Column(name = "suggested_fix", columnDefinition = "TEXT")
  private String suggestedFix;

  @Column(name = "confidence")
  private Double confidence;

  @Column(name = "tool_confirmed", nullable = false)
  private boolean toolConfirmed = false;

  @Column(name = "analyzer", length = 100)
  private String analyzer;

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

  public String getRule() {
    return rule;
  }

  public void setRule(String rule) {
    this.rule = rule;
  }

  public String getEvidenceSnippet() {
    return evidenceSnippet;
  }

  public void setEvidenceSnippet(String evidenceSnippet) {
    this.evidenceSnippet = evidenceSnippet;
  }

  public String getExplanation() {
    return explanation;
  }

  public void setExplanation(String explanation) {
    this.explanation = explanation;
  }

  public String getSuggestedFix() {
    return suggestedFix;
  }

  public void setSuggestedFix(String suggestedFix) {
    this.suggestedFix = suggestedFix;
  }

  public Double getConfidence() {
    return confidence;
  }

  public void setConfidence(Double confidence) {
    this.confidence = confidence;
  }

  public boolean isToolConfirmed() {
    return toolConfirmed;
  }

  public void setToolConfirmed(boolean toolConfirmed) {
    this.toolConfirmed = toolConfirmed;
  }

  public String getAnalyzer() {
    return analyzer;
  }

  public void setAnalyzer(String analyzer) {
    this.analyzer = analyzer;
  }
}
