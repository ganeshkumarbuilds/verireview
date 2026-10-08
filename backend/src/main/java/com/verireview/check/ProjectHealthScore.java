package com.verireview.check;

import com.verireview.common.BaseEntity;
import com.verireview.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Computed project health score stored for trending and dashboard display.
 * Formula documented in HEALTH_SCORE.md:
 * - Generate: 25% (last generation success, iterations, issues)
 * - Review: 25% (deterministic tool coverage, AI review quality, finding density)
 * - Fix: 25% (fix rate, verification rate, no regressions)
 * - Verify: 25% (gate pass rate, no new critical/high)
 * 
 * Each component 0-100, weighted equally.
 */
@Entity
@Table(
    name = "project_health_scores",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_health_scores_project_latest",
        columnNames = {"project_id", "last_computed_at"}
    )
)
public class ProjectHealthScore extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @Column(name = "score", nullable = false)
  private int score = 0;

  @Column(name = "generate_score", nullable = false)
  private int generateScore = 0;

  @Column(name = "review_score", nullable = false)
  private int reviewScore = 0;

  @Column(name = "fix_score", nullable = false)
  private int fixScore = 0;

  @Column(name = "verify_score", nullable = false)
  private int verifyScore = 0;

  @Column(name = "open_critical_high", nullable = false)
  private int openCriticalHigh = 0;

  @Column(name = "open_findings_total", nullable = false)
  private int openFindingsTotal = 0;

  @Column(name = "fixed_verified_ratio")
  private Double fixedVerifiedRatio;

  @Column(name = "last_computed_at", nullable = false)
  private Instant lastComputedAt;

  public ProjectHealthScore() {
  }

  public ProjectHealthScore(Project project) {
    this.project = project;
    this.lastComputedAt = Instant.now();
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public int getScore() {
    return score;
  }

  public void setScore(int score) {
    this.score = score;
  }

  public int getGenerateScore() {
    return generateScore;
  }

  public void setGenerateScore(int generateScore) {
    this.generateScore = generateScore;
  }

  public int getReviewScore() {
    return reviewScore;
  }

  public void setReviewScore(int reviewScore) {
    this.reviewScore = reviewScore;
  }

  public int getFixScore() {
    return fixScore;
  }

  public void setFixScore(int fixScore) {
    this.fixScore = fixScore;
  }

  public int getVerifyScore() {
    return verifyScore;
  }

  public void setVerifyScore(int verifyScore) {
    this.verifyScore = verifyScore;
  }

  public int getOpenCriticalHigh() {
    return openCriticalHigh;
  }

  public void setOpenCriticalHigh(int openCriticalHigh) {
    this.openCriticalHigh = openCriticalHigh;
  }

  public int getOpenFindingsTotal() {
    return openFindingsTotal;
  }

  public void setOpenFindingsTotal(int openFindingsTotal) {
    this.openFindingsTotal = openFindingsTotal;
  }

  public Double getFixedVerifiedRatio() {
    return fixedVerifiedRatio;
  }

  public void setFixedVerifiedRatio(Double fixedVerifiedRatio) {
    this.fixedVerifiedRatio = fixedVerifiedRatio;
  }

  public Instant getLastComputedAt() {
    return lastComputedAt;
  }

  public void setLastComputedAt(Instant lastComputedAt) {
    this.lastComputedAt = lastComputedAt;
  }
}