package com.verireview.fix;

import com.verireview.common.BaseEntity;
import com.verireview.review.Finding;
import com.verireview.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Explicit user approval to fix one finding. No patch exists without this row.
 * At most one open request per finding (partial unique index in V1).
 */
@Entity
@Table(name = "fix_requests")
public class FixRequest extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "finding_id", nullable = false)
  private Finding finding;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "requested_by", nullable = false)
  private User requestedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private FixRequestStatus status = FixRequestStatus.PENDING;

  @Column(name = "scope_note", columnDefinition = "TEXT")
  private String scopeNote;

  public FixRequest() {
  }

  public FixRequest(Finding finding, User requestedBy) {
    this.finding = finding;
    this.requestedBy = requestedBy;
  }

  public Finding getFinding() {
    return finding;
  }

  public void setFinding(Finding finding) {
    this.finding = finding;
  }

  public User getRequestedBy() {
    return requestedBy;
  }

  public void setRequestedBy(User requestedBy) {
    this.requestedBy = requestedBy;
  }

  public FixRequestStatus getStatus() {
    return status;
  }

  public void setStatus(FixRequestStatus status) {
    this.status = status;
  }

  public String getScopeNote() {
    return scopeNote;
  }

  public void setScopeNote(String scopeNote) {
    this.scopeNote = scopeNote;
  }
}
