package com.verireview.security;

import com.verireview.user.Role;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.util.stream.Collectors;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads principals by email (case-insensitive; emails are stored normalized).
 * Read-only transaction; roles are fetched with the user for authorities.
 */
@Service
public class VeriReviewUserDetailsService implements UserDetailsService {

  private final UserRepository users;

  public VeriReviewUserDetailsService(UserRepository users) {
    this.users = users;
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    User user = users.findWithRolesByEmail(normalize(email))
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    return new VeriReviewUserDetails(
        user.getId(),
        user.getEmail(),
        user.getPasswordHash(),
        user.isEnabled(),
        user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()));
  }

  static String normalize(String email) {
    return email == null ? null : email.trim().toLowerCase();
  }
}
