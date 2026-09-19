package com.verireview.user;

import com.verireview.security.VeriReviewUserDetails;
import com.verireview.user.dto.LoginRequest;
import com.verireview.user.dto.PasswordResetRequest;
import com.verireview.user.dto.RefreshRequest;
import com.verireview.user.dto.RegisterRequest;
import com.verireview.user.dto.TokenResponse;
import com.verireview.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public auth boundary (API_DESIGN §2). Intentionally thin: validate DTO,
 * delegate to {@link AuthService}, return DTO. No entities, no hashes, no
 * auth logic here.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService auth;
  private final UserService userService;

  public AuthController(AuthService auth, UserService userService) {
    this.auth = auth;
    this.userService = userService;
  }

  @PostMapping("/register")
  public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(auth.login(request));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
    return ResponseEntity.ok(auth.refresh(request));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody RefreshRequest request) {
    auth.logout(principal.getId(), request);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password-reset/request")
  public ResponseEntity<Void> requestPasswordReset(
      @Valid @RequestBody PasswordResetRequest request) {
    auth.requestPasswordReset(request);
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/me")
  public ResponseEntity<UserResponse> me(
      @AuthenticationPrincipal VeriReviewUserDetails principal) {
    return ResponseEntity.ok(userService.me(principal.getId()));
  }
}
