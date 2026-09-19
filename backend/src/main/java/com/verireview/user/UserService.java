package com.verireview.user;

import com.verireview.user.dto.UserResponse;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * User queries behind the auth boundary. Cross-user reads answer 404 (not
 * 403) so callers cannot enumerate accounts (SECURITY_DESIGN T6). This is the
 * ownership-predicate pattern that Phase 5 reuses for project-scoped reads.
 */
@Service
public class UserService {

  private final UserRepository users;

  public UserService(UserRepository users) {
    this.users = users;
  }

  @Transactional(readOnly = true)
  public UserResponse me(UUID userId) {
    return toResponse(getUser(userId));
  }

  /**
   * Returns a profile only to its owner (or an ADMIN for support reads).
   * Anyone else — including for user ids that do not exist — gets 404.
   */
  @Transactional(readOnly = true)
  public UserResponse getAs(UUID requesterId, UUID targetId, boolean requesterAdmin) {
    if (!requesterId.equals(targetId) && !requesterAdmin) {
      throw notFound();
    }
    return toResponse(getUser(targetId));
  }

  @Transactional(readOnly = true)
  public Page<UserResponse> list(Pageable pageable) {
    return users.findAll(pageable).map(UserService::toResponse);
  }

  private User getUser(UUID userId) {
    return users.findById(userId).orElseThrow(UserService::notFound);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
  }

  /**
   * Manual mapping (one trivial conversion; MapStruct wiring arrives when the
   * DTO surface grows — see ADR-003). The password hash never leaves this
   * service.
   */
  static UserResponse toResponse(User user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()),
        user.isEnabled(),
        user.getCreatedAt());
  }
}
