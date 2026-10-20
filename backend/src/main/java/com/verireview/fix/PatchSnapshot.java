package com.verireview.fix;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Exact pre-apply content of a single file a patch touches.
 *
 * <p>Captured when a patch is applied so a later Revert can deterministically
 * restore the previous file content (AGENTS.md rule 20: destructive changes are
 * reversible). When {@code existed} is false the patch created the file, so a
 * revert deletes it.
 */
@Entity
@Table(name = "patch_snapshots")
public class PatchSnapshot extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "patch_id", nullable = false)
  private Patch patch;

  @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
  private String filePath;

  @Column(name = "existed", nullable = false)
  private boolean existed = true;

  @Column(name = "previous_content", columnDefinition = "TEXT")
  private String previousContent;

  public PatchSnapshot() {
  }

  public PatchSnapshot(Patch patch, String filePath, boolean existed, String previousContent) {
    this.patch = patch;
    this.filePath = filePath;
    this.existed = existed;
    this.previousContent = previousContent;
  }

  public Patch getPatch() {
    return patch;
  }

  public void setPatch(Patch patch) {
    this.patch = patch;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public boolean isExisted() {
    return existed;
  }

  public void setExisted(boolean existed) {
    this.existed = existed;
  }

  public String getPreviousContent() {
    return previousContent;
  }

  public void setPreviousContent(String previousContent) {
    this.previousContent = previousContent;
  }
}
