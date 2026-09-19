package com.verireview.fix;

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

/**
 * Controlled unified diff for a fix request. Stored only — never auto-applied
 * to the canonical project tree (that happens in the sandbox, Phase 10+).
 */
@Entity
@Table(name = "patches")
public class Patch extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "fix_request_id", nullable = false)
  private FixRequest fixRequest;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id")
  private Project project;

  @Column(name = "diff", nullable = false, columnDefinition = "TEXT")
  private String diff;

  @Column(name = "files_changed", nullable = false)
  private int filesChanged;

  @Column(name = "additions", nullable = false)
  private int additions;

  @Column(name = "deletions", nullable = false)
  private int deletions;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PatchStatus status = PatchStatus.PROPOSED;

  @Column(name = "validation_error", columnDefinition = "TEXT")
  private String validationError;

  public Patch() {
  }

  public Patch(FixRequest fixRequest, String diff) {
    this.fixRequest = fixRequest;
    this.diff = diff;
  }

  public FixRequest getFixRequest() {
    return fixRequest;
  }

  public void setFixRequest(FixRequest fixRequest) {
    this.fixRequest = fixRequest;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public String getDiff() {
    return diff;
  }

  public void setDiff(String diff) {
    this.diff = diff;
  }

  public int getFilesChanged() {
    return filesChanged;
  }

  public void setFilesChanged(int filesChanged) {
    this.filesChanged = filesChanged;
  }

  public int getAdditions() {
    return additions;
  }

  public void setAdditions(int additions) {
    this.additions = additions;
  }

  public int getDeletions() {
    return deletions;
  }

  public void setDeletions(int deletions) {
    this.deletions = deletions;
  }

  public PatchStatus getStatus() {
    return status;
  }

  public void setStatus(PatchStatus status) {
    this.status = status;
  }

  public String getValidationError() {
    return validationError;
  }

  public void setValidationError(String validationError) {
    this.validationError = validationError;
  }
}
