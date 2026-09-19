package com.verireview.audit;

import com.verireview.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Append-only security trail. No {@code updated_at}, no update/delete access;
 * the actor reference survives user deletion via {@code ON DELETE SET NULL}.
 */
@Entity
@Table(name = "audit_logs")
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id")
  private User actor;

  @Column(name = "action", nullable = false, length = 100)
  private String action;

  @Column(name = "entity_type", nullable = false, length = 100)
  private String entityType;

  @Column(name = "entity_id", nullable = false, length = 100)
  private String entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private String metadata;

  @Column(name = "ip", length = 45)
  private String ip;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public AuditLog() {
  }

  public AuditLog(String action, String entityType, String entityId) {
    this.action = action;
    this.entityType = entityType;
    this.entityId = entityId;
  }

  public UUID getId() {
    return id;
  }

  public User getActor() {
    return actor;
  }

  public void setActor(User actor) {
    this.actor = actor;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public String getEntityId() {
    return entityId;
  }

  public void setEntityId(String entityId) {
    this.entityId = entityId;
  }

  public String getMetadata() {
    return metadata;
  }

  public void setMetadata(String metadata) {
    this.metadata = metadata;
  }

  public String getIp() {
    return ip;
  }

  public void setIp(String ip) {
    this.ip = ip;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
