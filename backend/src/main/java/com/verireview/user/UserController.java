package com.verireview.user;

import com.verireview.user.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only user administration (API_DESIGN §2). The filter chain already
 * restricts {@code /api/v1/users/**} to ADMIN; the annotation below is the
 * second layer (belt and suspenders, SECURITY_DESIGN §2).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Page<UserResponse>> list(Pageable pageable) {
    return ResponseEntity.ok(userService.list(pageable));
  }
}
