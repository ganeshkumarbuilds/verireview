package com.verireview.generation;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One proposed file-move plan for a generated workspace. Nothing moves until
 * the user explicitly applies a PROPOSED row, and the applied plan keeps a
 * backup reference so Revert can restore the original tree.
 */
@Entity
@Table(name = "generation_restructure_proposals")
public class GenerationRestructure extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @Column(name = "iteration", nullable = false)
  private int iteration;

  @Enumerated(EnumType.STRING)
  @Column(name = "preset", nullable = false, length = 30)
  private Preset preset;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "proposal_json", nullable = false, columnDefinition = "TEXT")
  private String proposalJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private Status status = Status.PROPOSED;

  @Column(name = "backup_ref", length = 1000)
  private String backupRef;

  public GenerationRestructure() {
  }

  public GenerationRestructure(Generation generation, int iteration, Preset preset,
      String description, String proposalJson) {
    this.generation = generation;
    this.iteration = iteration;
    this.preset = preset;
    this.description = description;
    this.proposalJson = proposalJson;
  }

  public Generation getGeneration() {
    return generation;
  }

  public void setGeneration(Generation generation) {
    this.generation = generation;
  }

  public int getIteration() {
    return iteration;
  }

  public void setIteration(int iteration) {
    this.iteration = iteration;
  }

  public Preset getPreset() {
    return preset;
  }

  public void setPreset(Preset preset) {
    this.preset = preset;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getProposalJson() {
    return proposalJson;
  }

  public void setProposalJson(String proposalJson) {
    this.proposalJson = proposalJson;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public String getBackupRef() {
    return backupRef;
  }

  public void setBackupRef(String backupRef) {
    this.backupRef = backupRef;
  }

  public enum Preset {
    MVC,
    LAYERED,
    FEATURE_FOLDERS,
    CUSTOM
  }

  public enum Status {
    PROPOSED,
    APPLIED,
    REVERTED,
    REJECTED
  }
}
