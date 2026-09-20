package com.verireview.agent;

import com.verireview.common.BaseEntity;
import com.verireview.fix.FixRequest;
import com.verireview.generation.Generation;
import com.verireview.project.Project;
import com.verireview.review.Review;
import com.verireview.verification.VerificationRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Trace of every agent invocation (agent, model, prompt version, input hash,
 * status, duration). The AI service proposes; this row is written by the
 * backend when it invokes the service and records the outcome.
 */
@Entity
@Table(name = "agent_executions")
public class AgentExecution extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id")
  private Project project;

  @Enumerated(EnumType.STRING)
  @Column(name = "agent_type", nullable = false, length = 20)
  private AgentType agentType;

  @Column(name = "model", nullable = false, length = 200)
  private String model;

  @Column(name = "prompt_version", nullable = false, length = 50)
  private String promptVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AgentExecutionStatus status = AgentExecutionStatus.PENDING;

  @Column(name = "input_hash", nullable = false, length = 64)
  private String inputHash;

  @Column(name = "output_ref", length = 1000)
  private String outputRef;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "error", columnDefinition = "TEXT")
  private String error;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "review_id")
  private Review review;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "fix_request_id")
  private FixRequest fixRequest;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "verification_run_id")
  private VerificationRun verificationRun;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "generation_id")
  private Generation generation;

  public AgentExecution() {
  }

  public AgentExecution(Project project, AgentType agentType, String model,
      String promptVersion, String inputHash) {
    this.project = project;
    this.agentType = agentType;
    this.model = model;
    this.promptVersion = promptVersion;
    this.inputHash = inputHash;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public AgentType getAgentType() {
    return agentType;
  }

  public void setAgentType(AgentType agentType) {
    this.agentType = agentType;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getPromptVersion() {
    return promptVersion;
  }

  public void setPromptVersion(String promptVersion) {
    this.promptVersion = promptVersion;
  }

  public AgentExecutionStatus getStatus() {
    return status;
  }

  public void setStatus(AgentExecutionStatus status) {
    this.status = status;
  }

  public String getInputHash() {
    return inputHash;
  }

  public void setInputHash(String inputHash) {
    this.inputHash = inputHash;
  }

  public String getOutputRef() {
    return outputRef;
  }

  public void setOutputRef(String outputRef) {
    this.outputRef = outputRef;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public Review getReview() {
    return review;
  }

  public void setReview(Review review) {
    this.review = review;
  }

  public FixRequest getFixRequest() {
    return fixRequest;
  }

  public void setFixRequest(FixRequest fixRequest) {
    this.fixRequest = fixRequest;
  }

  public VerificationRun getVerificationRun() {
    return verificationRun;
  }

  public void setVerificationRun(VerificationRun verificationRun) {
    this.verificationRun = verificationRun;
  }

  public Generation getGeneration() {
    return generation;
  }

  public void setGeneration(Generation generation) {
    this.generation = generation;
  }
}
