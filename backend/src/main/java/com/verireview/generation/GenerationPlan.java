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
 * The validated project plan for one generation iteration, stored as
 * machine-readable JSON (architecture, dependencies, directories, APIs,
 * steps, files). Persisted so a run can be resumed and inspected without
 * re-invoking the Planning Agent; the Coding Agent consumes exactly the
 * files listed here.
 */
@Entity
@Table(
    name = "generation_plans",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_generation_plans_generation_iteration",
        columnNames = {"generation_id", "iteration"}))
public class GenerationPlan extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "generation_id", nullable = false)
  private Generation generation;

  @Column(name = "iteration", nullable = false)
  private int iteration;

  @Column(name = "plan_json", nullable = false, columnDefinition = "TEXT")
  private String planJson;

  @Column(name = "file_count", nullable = false)
  private int fileCount;

  public GenerationPlan() {
  }

  public GenerationPlan(Generation generation, int iteration, String planJson, int fileCount) {
    this.generation = generation;
    this.iteration = iteration;
    this.planJson = planJson;
    this.fileCount = fileCount;
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

  public String getPlanJson() {
    return planJson;
  }

  public void setPlanJson(String planJson) {
    this.planJson = planJson;
  }

  public int getFileCount() {
    return fileCount;
  }

  public void setFileCount(int fileCount) {
    this.fileCount = fileCount;
  }
}
