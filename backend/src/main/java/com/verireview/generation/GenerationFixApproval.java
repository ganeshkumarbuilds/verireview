package com.verireview.generation;

import com.verireview.common.BaseEntity;
import com.verireview.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "generation_fix_approvals")
public class GenerationFixApproval extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "round_number", nullable = false)
  private int roundNumber;

  @Column(name = "selected_finding_ids", nullable = false, columnDefinition = "TEXT")
  private String selectedFindingIds;

  @Column(name = "before_finding_count", nullable = false)
  private int beforeFindingCount;

  @Column(name = "after_finding_count")
  private Integer afterFindingCount;

  @Column(name = "status", nullable = false, length = 20)
  private String status = "APPROVED";

  @Column(name = "approved_at", nullable = false)
  private Instant approvedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected GenerationFixApproval() {
  }

  public GenerationFixApproval(
      Generation generation,
      User user,
      int roundNumber,
      String selectedFindingIds,
      int beforeFindingCount,
      Instant approvedAt) {
    this.generation = generation;
    this.user = user;
    this.roundNumber = roundNumber;
    this.selectedFindingIds = selectedFindingIds;
    this.beforeFindingCount = beforeFindingCount;
    this.approvedAt = approvedAt;
  }

  public Generation getGeneration() { return generation; }
  public User getUser() { return user; }
  public int getRoundNumber() { return roundNumber; }
  public String getSelectedFindingIds() { return selectedFindingIds; }
  public int getBeforeFindingCount() { return beforeFindingCount; }
  public Integer getAfterFindingCount() { return afterFindingCount; }
  public String getStatus() { return status; }
  public Instant getApprovedAt() { return approvedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public void setStatus(String status) { this.status = status; }
  public void setAfterFindingCount(Integer count) { this.afterFindingCount = count; }
  public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
