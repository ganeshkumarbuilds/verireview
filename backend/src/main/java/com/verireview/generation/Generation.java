package com.verireview.generation;

import com.verireview.common.BaseEntity;
import com.verireview.project.Project;
import com.verireview.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A project-generation job. The backend owns this row through every state
 * (QUEUED → PLANNING → GENERATING → REVIEWING → COMPLETED / FAILED).
 *
 * <p>Security: this row NEVER stores secrets. The database password and the
 * AI API key travel in the create request, are held in memory only for the
 * run ({@link GenerationSecrets}), and are discarded afterwards. Only
 * non-secret connection metadata (host, port, name, user, ssl mode) and the
 * AI provider/model/base-url are persisted.
 */
@Entity
@Table(
    name = "generations",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_generations_owner_name", columnNames = {"owner_id", "name"}))
public class Generation extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "requirement", nullable = false, columnDefinition = "TEXT")
  private String requirement;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "backend_stack", nullable = false, length = 30)
  private GenerationBackend backendStack;

  @Enumerated(EnumType.STRING)
  @Column(name = "frontend_stack", nullable = false, length = 30)
  private GenerationFrontend frontendStack;

  @Enumerated(EnumType.STRING)
  @Column(name = "database_type", nullable = false, length = 30)
  private GenerationDatabase databaseType;

  @Column(name = "db_host", length = 500)
  private String dbHost;

  @Column(name = "db_port")
  private Integer dbPort;

  @Column(name = "db_name", length = 200)
  private String dbName;

  @Column(name = "db_username", length = 200)
  private String dbUsername;

  @Column(name = "db_ssl_mode", length = 50)
  private String dbSslMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "ai_provider", nullable = false, length = 30)
  private GenerationAiProvider aiProvider;

  @Column(name = "ai_model", nullable = false, length = 200)
  private String aiModel;

  @Column(name = "ai_base_url", length = 500)
  private String aiBaseUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private GenerationStatus status = GenerationStatus.QUEUED;

  /**
   * Hard cap on fix-loop iterations so an endless agent loop is impossible.
   * Enforced by {@link GenerationService} before any new iteration starts.
   */
  public static final int MAX_ITERATIONS = 5;

  @Column(name = "iteration", nullable = false)
  private int iteration;

  @Column(name = "max_iterations", nullable = false)
  private int maxIterations = MAX_ITERATIONS;

  @Column(name = "error", columnDefinition = "TEXT")
  private String error;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id")
  private Project project;

  public Generation() {
  }

  public User getOwner() {
    return owner;
  }

  public void setOwner(User owner) {
    this.owner = owner;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getRequirement() {
    return requirement;
  }

  public void setRequirement(String requirement) {
    this.requirement = requirement;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public GenerationBackend getBackendStack() {
    return backendStack;
  }

  public void setBackendStack(GenerationBackend backendStack) {
    this.backendStack = backendStack;
  }

  public GenerationFrontend getFrontendStack() {
    return frontendStack;
  }

  public void setFrontendStack(GenerationFrontend frontendStack) {
    this.frontendStack = frontendStack;
  }

  public GenerationDatabase getDatabaseType() {
    return databaseType;
  }

  public void setDatabaseType(GenerationDatabase databaseType) {
    this.databaseType = databaseType;
  }

  public String getDbHost() {
    return dbHost;
  }

  public void setDbHost(String dbHost) {
    this.dbHost = dbHost;
  }

  public Integer getDbPort() {
    return dbPort;
  }

  public void setDbPort(Integer dbPort) {
    this.dbPort = dbPort;
  }

  public String getDbName() {
    return dbName;
  }

  public void setDbName(String dbName) {
    this.dbName = dbName;
  }

  public String getDbUsername() {
    return dbUsername;
  }

  public void setDbUsername(String dbUsername) {
    this.dbUsername = dbUsername;
  }

  public String getDbSslMode() {
    return dbSslMode;
  }

  public void setDbSslMode(String dbSslMode) {
    this.dbSslMode = dbSslMode;
  }

  public GenerationAiProvider getAiProvider() {
    return aiProvider;
  }

  public void setAiProvider(GenerationAiProvider aiProvider) {
    this.aiProvider = aiProvider;
  }

  public String getAiModel() {
    return aiModel;
  }

  public void setAiModel(String aiModel) {
    this.aiModel = aiModel;
  }

  public String getAiBaseUrl() {
    return aiBaseUrl;
  }

  public void setAiBaseUrl(String aiBaseUrl) {
    this.aiBaseUrl = aiBaseUrl;
  }

  public GenerationStatus getStatus() {
    return status;
  }

  public void setStatus(GenerationStatus status) {
    this.status = status;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public int getIteration() {
    return iteration;
  }

  public void setIteration(int iteration) {
    this.iteration = iteration;
  }

  public int getMaxIterations() {
    return maxIterations;
  }

  public void setMaxIterations(int maxIterations) {
    this.maxIterations = maxIterations;
  }
}
