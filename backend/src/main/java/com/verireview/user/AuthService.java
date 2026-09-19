package com.verireview.user;

import com.verireview.audit.AuditService;
import com.verireview.security.JwtService;
import com.verireview.security.RefreshTokenStore;
import com.verireview.user.dto.LoginRequest;
import com.verireview.user.dto.PasswordResetRequest;
import com.verireview.user.dto.RefreshRequest;
import com.verireview.user.dto.RegisterRequest;
import com.verireview.user.dto.TokenResponse;
import io.jsonwebtoken.JwtException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 3 identity workflows (register/login/refresh/logout). All password
 * and token logic lives here — controllers stay thin and never see entities
 * or hashes. Failure responses are deliberately generic (401) so callers
 * cannot probe which half of a credential was wrong.
 */
@Service
public class AuthService {

  static final int MIN_PASSWORD_LENGTH = 8;

  /**
   * Minimal v1 breached-password denylist (SECURITY_DESIGN §2). Compared
   * case-insensitively against the exact password.
   */
  private static final Set<String> BREACHED_PASSWORDS = Set.of(
      "password1234", "123456789012", "qwertyuiop12", "letmein123456",
      "welcome12345", "admin12345678", "password123456");

  private final UserRepository users;
  private final RoleRepository roles;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final RefreshTokenStore refreshTokens;
  private final AuditService audits;

  public AuthService(
      UserRepository users,
      RoleRepository roles,
      PasswordEncoder passwords,
      JwtService jwt,
      RefreshTokenStore refreshTokens,
      AuditService audits) {
    this.users = users;
    this.roles = roles;
    this.passwords = passwords;
    this.jwt = jwt;
    this.refreshTokens = refreshTokens;
    this.audits = audits;
  }

  @Transactional
  public TokenResponse register(RegisterRequest request) {
    String email = normalize(request.email());
    assertPasswordAcceptable(request.password());
    if (users.findByEmail(email).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
    }
    Role userRole = roles.findByName("USER")
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Role seed is missing"));
    User user = new User(email, passwords.encode(request.password()));
    user.setDisplayName(request.displayName());
    user.getRoles().add(userRole);
    users.save(user);
    audits.record(user, "AUTH_REGISTER", "user", user.getId().toString());
    return issuePair(user);
  }

  @Transactional
  public TokenResponse login(LoginRequest request) {
    String email = normalize(request.email());
    User user = users.findWithRolesByEmail(email).orElse(null);
    if (user == null || !user.isEnabled()
        || !passwords.matches(request.password(), user.getPasswordHash())) {
      audits.record(null, "AUTH_LOGIN_FAILED", "user", email);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
    audits.record(user, "AUTH_LOGIN", "user", user.getId().toString());
    return issuePair(user);
  }

  /**
   * Rotates a refresh token into a fresh pair (ADR-009). Presenting an
   * already-rotated refresh token revokes the whole family (reuse detection).
   */
  @Transactional
  public TokenResponse refresh(RefreshRequest request) {
    JwtService.ParsedToken parsed = parseRefresh(request.refreshToken());
    User user = users.findWithRolesByEmail(parsed.email()).orElse(null);
    if (user == null || !user.isEnabled() || !user.getId().equals(parsed.userId())) {
      throw unauthorized();
    }
    final UUID owner;
    try {
      owner = refreshTokens.consume(parsed.jti());
    } catch (RefreshTokenStore.UnknownRefreshTokenException e) {
      refreshTokens.revokeAll(user.getId());
      audits.record(user, "AUTH_REFRESH_REUSE", "user", user.getId().toString());
      throw unauthorized();
    }
    if (!owner.equals(user.getId())) {
      throw unauthorized();
    }
    audits.record(user, "AUTH_REFRESH", "user", user.getId().toString());
    return issuePair(user);
  }

  @Transactional
  public void logout(UUID userId, RefreshRequest request) {    try {
      JwtService.ParsedToken parsed = jwt.parse(request.refreshToken());
      if (JwtService.TYPE_REFRESH.equals(parsed.type())
          && userId.equals(parsed.userId())) {
        refreshTokens.revoke(parsed.jti());
      }
    } catch (JwtException | IllegalArgumentException e) {
      // Logout is idempotent: an unusable refresh token is already "logged out".
    }
    User user = users.findById(userId).orElse(null);
    audits.record(user, "AUTH_LOGOUT", "user", userId.toString());
  }

  /**
   * Records a password-reset request. Always succeeds (even for unknown
   * emails) so callers cannot enumerate accounts. There is no mail delivery
   * yet — the audit row lets an administrator assist; token-based reset
   * arrives with the mail integration.
   */
  @Transactional
  public void requestPasswordReset(PasswordResetRequest request) {
    String email = normalize(request.email());
    User user = users.findByEmail(email).orElse(null);
    audits.record(user, "PASSWORD_RESET_REQUESTED", "user",
        user == null ? email : user.getId().toString());
  }

  private TokenResponse issuePair(User user) {
    Set<String> roleNames =
        user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
    String access = jwt.issueAccess(user.getId(), user.getEmail(), roleNames);
    JwtService.IssuedRefresh refresh = jwt.issueRefresh(user.getId(), user.getEmail());
    refreshTokens.register(user.getId(), refresh.jti(), refresh.expiresAt());
    return new TokenResponse(access, refresh.token(), "Bearer", jwt.accessExpiresInSeconds());
  }

  private JwtService.ParsedToken parseRefresh(String token) {
    final JwtService.ParsedToken parsed;
    try {
      parsed = jwt.parse(token);
    } catch (JwtException | IllegalArgumentException e) {
      throw unauthorized();
    }
    if (!JwtService.TYPE_REFRESH.equals(parsed.type()) || parsed.jti() == null) {
      throw unauthorized();
    }
    return parsed;
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
  }

  static void assertPasswordAcceptable(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH
        || BREACHED_PASSWORDS.contains(password.toLowerCase())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password does not meet the policy");
    }
  }

  static String normalize(String email) {
    return email.trim().toLowerCase();
  }
}
