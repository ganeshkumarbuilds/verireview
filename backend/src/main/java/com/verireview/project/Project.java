package com.verireview.project;

import com.verireview.common.BaseEntity;
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
import java.time.Instant;

/**
 * Aggregate root for everything code-related. Soft-deleted via
 * {@code deletedAt}; history (reviews, findings) is preserved.
 */
@Entity
@Table(
    name = "projects",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_projects_owner_name", columnNames = {"owner_id", "name"}))
public class Project extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private ProjectSourceType sourceType;

  @Column(name = "language", length = 50)
  private String language;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ProjectStatus status = ProjectStatus.ACTIVE;

  @Column(name = "storage_ref", length = 500)
  private String storageRef;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public Project() {
  }

  public Project(User owner, String name, ProjectSourceType sourceType) {
    this.owner = owner;
    this.name = name;
    this.sourceType = sourceType;
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

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public ProjectSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(ProjectSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public ProjectStatus getStatus() {
    return status;
  }

  public void setStatus(ProjectStatus status) {
    this.status = status;
  }

  public String getStorageRef() {
    return storageRef;
  }

  public void setStorageRef(String storageRef) {
    this.storageRef = storageRef;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }
}
