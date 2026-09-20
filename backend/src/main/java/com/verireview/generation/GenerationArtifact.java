package com.verireview.generation;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Metadata for one verified project artifact produced by a generation
 * iteration. Facts only (counts, hash, server-side storage reference) —
 * file contents live on disk and secrets never appear here. The ZIP
 * download of a later phase is built from the referenced artifact, never
 * from frontend input.
 */
@Entity
@Table(name = "generation_artifacts")
public class GenerationArtifact extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @Column(name = "iteration", nullable = false)
  private int iteration;

  @Column(name = "file_count", nullable = false)
  private int fileCount;

  @Column(name = "total_chars", nullable = false)
  private long totalChars;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "storage_ref", nullable = false, length = 1000)
  private String storageRef;

  public GenerationArtifact() {
  }

  public GenerationArtifact(Generation generation, int iteration, int fileCount,
      long totalChars, String sha256, String storageRef) {
    this.generation = generation;
    this.iteration = iteration;
    this.fileCount = fileCount;
    this.totalChars = totalChars;
    this.sha256 = sha256;
    this.storageRef = storageRef;
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

  public int getFileCount() {
    return fileCount;
  }

  public void setFileCount(int fileCount) {
    this.fileCount = fileCount;
  }

  public long getTotalChars() {
    return totalChars;
  }

  public void setTotalChars(long totalChars) {
    this.totalChars = totalChars;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public String getStorageRef() {
    return storageRef;
  }

  public void setStorageRef(String storageRef) {
    this.storageRef = storageRef;
  }
}
