package com.verireview.project;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Normalized file inventory row. Either inline {@code content} or an external
 * {@code contentRef} is set (XOR enforced by the V1 CHECK constraint).
 */
@Entity
@Table(
    name = "project_files",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_project_files_project_path", columnNames = {"project_id", "path"}))
public class ProjectFile extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @Column(name = "path", nullable = false, length = 1000)
  private String path;

  @Column(name = "language", length = 50)
  private String language;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "content", columnDefinition = "TEXT")
  private String content;

  @Column(name = "content_ref", length = 1000)
  private String contentRef;

  public ProjectFile() {
  }

  public ProjectFile(Project project, String path, long sizeBytes, String sha256) {
    this.project = project;
    this.path = path;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getContentRef() {
    return contentRef;
  }

  public void setContentRef(String contentRef) {
    this.contentRef = contentRef;
  }
}
