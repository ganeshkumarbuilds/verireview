package com.verireview.user;

import com.verireview.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * Identity and ownership root. Table is {@code users} (plural is not a
 * reserved word). Password hashes only — never plaintext (Phase 3 owns auth).
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

  @Column(name = "email", nullable = false, unique = true, length = 255)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "display_name", length = 100)
  private String displayName;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<Role> roles = new HashSet<>();

  public User() {
  }

  public User(String email, String passwordHash) {
    this.email = email;
    this.passwordHash = passwordHash;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Set<Role> getRoles() {
    return roles;
  }
}
