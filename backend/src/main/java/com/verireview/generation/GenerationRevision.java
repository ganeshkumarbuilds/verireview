package com.verireview.generation;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One version of a generation's task. The initial requirement is revision 1;
 * every user modification appends a new revision instead of rewriting
 * history, so a later phase can diff what changed and resume from the
 * current project state rather than restarting blindly from zero.
 * Holds task text only — never secrets.
 */
@Entity
@Table(
    name = "generation_revisions",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_generation_revisions_generation_number",
        columnNames = {"generation_id", "revision_number"}))
public class GenerationRevision extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @Column(name = "revision_number", nullable = false)
  private int revisionNumber;

  @Column(name = "requirement", nullable = false, columnDefinition = "TEXT")
  private String requirement;

  public GenerationRevision() {
  }

  public GenerationRevision(Generation generation, int revisionNumber, String requirement) {
    this.generation = generation;
    this.revisionNumber = revisionNumber;
    this.requirement = requirement;
  }

  public Generation getGeneration() {
    return generation;
  }

  public void setGeneration(Generation generation) {
    this.generation = generation;
  }

  public int getRevisionNumber() {
    return revisionNumber;
  }

  public void setRevisionNumber(int revisionNumber) {
    this.revisionNumber = revisionNumber;
  }

  public String getRequirement() {
    return requirement;
  }

  public void setRequirement(String requirement) {
    this.requirement = requirement;
  }
}
