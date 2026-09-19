package com.verireview.security;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal backed by the {@code users} table. Authorities are
 * {@code ROLE_<NAME>} from the seeded roles (USER, ADMIN).
 */
public class VeriReviewUserDetails implements UserDetails {

  private final UUID id;
  private final String email;
  private final String passwordHash;
  private final boolean enabled;
  private final Set<GrantedAuthority> authorities;

  public VeriReviewUserDetails(
      UUID id, String email, String passwordHash, boolean enabled, Set<String> roles) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
    this.enabled = enabled;
    this.authorities = roles.stream()
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
        .collect(Collectors.toUnmodifiableSet());
  }

  public UUID getId() {
    return id;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities;
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}
