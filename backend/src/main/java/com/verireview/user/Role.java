package com.verireview.user;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * RBAC role reference data ({@code USER}, {@code ADMIN} seeded by Flyway V2).
 * Separate table so future roles need no user-row migration.
 */
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

  @Column(name = "name", nullable = false, unique = true, length = 50)
  private String name;

  public Role() {
  }

  public Role(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
