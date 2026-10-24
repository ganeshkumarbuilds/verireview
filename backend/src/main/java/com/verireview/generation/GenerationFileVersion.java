package com.verireview.generation;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "generation_file_versions")
public class GenerationFileVersion extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "approval_id", nullable = false)
  private GenerationFixApproval approval;

  @Column(name = "round_number", nullable = false)
  private int roundNumber;

  @Column(name = "file_path", nullable = false, length = 1000)
  private String filePath;

  @Column(name = "before_content", nullable = false, columnDefinition = "TEXT")
  private String beforeContent;

  @Column(name = "after_content", nullable = false, columnDefinition = "TEXT")
  private String afterContent;

  @Column(name = "reverted", nullable = false)
  private boolean reverted;

  protected GenerationFileVersion() {
  }

  public GenerationFileVersion(
      Generation generation, GenerationFixApproval approval, int roundNumber,
      String filePath, String beforeContent, String afterContent) {
    this.generation = generation;
    this.approval = approval;
    this.roundNumber = roundNumber;
    this.filePath = filePath;
    this.beforeContent = beforeContent;
    this.afterContent = afterContent;
  }

  public Generation getGeneration() { return generation; }
  public GenerationFixApproval getApproval() { return approval; }
  public int getRoundNumber() { return roundNumber; }
  public String getFilePath() { return filePath; }
  public String getBeforeContent() { return beforeContent; }
  public String getAfterContent() { return afterContent; }
  public boolean isReverted() { return reverted; }
  public void setReverted(boolean reverted) { this.reverted = reverted; }
}
