package com.verireview.security;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory refresh-token rotation allowlist (ADR-009, single-instance
 * Phase 3). Each refresh JWT {@code jti} is registered on issue and consumed
 * (revoked) on rotation. Presenting an already-rotated {@code jti} with a
 * still-valid signature means token reuse, so the whole family is revoked.
 * No database table (DATABASE_DESIGN keeps the refresh table absent); sessions
 * do not survive a restart by design.
 */
@Component
public class RefreshTokenStore {

  private record Session(UUID userId, Instant expiresAt) {
  }

  private final ConcurrentMap<String, Session> active = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Set<String>> byUser = new ConcurrentHashMap<>();

  public void register(UUID userId, String jti, Instant expiresAt) {
    purgeExpired();
    active.put(jti, new Session(userId, expiresAt));
    byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(jti);
  }

  /**
   * Consumes a refresh {@code jti} as part of rotation.
   *
   * @return the owning user id
   * @throws UnknownRefreshTokenException when the {@code jti} is not active
   *     (already rotated or revoked) — the caller must treat a
   *     signature-valid token in this state as reuse and revoke the family
   */
  public UUID consume(String jti) throws UnknownRefreshTokenException {
    Session session = active.remove(jti);
    if (session == null) {
      throw new UnknownRefreshTokenException();
    }
    Set<String> family = byUser.get(session.userId());
    if (family != null) {
      family.remove(jti);
    }
    if (session.expiresAt().isBefore(Instant.now())) {
      throw new UnknownRefreshTokenException();
    }
    return session.userId();
  }

  public void revoke(String jti) {
    Session session = active.remove(jti);
    if (session != null) {
      Set<String> family = byUser.get(session.userId());
      if (family != null) {
        family.remove(jti);
      }
    }
  }

  /** Revokes every session of a user (reuse detection, logout-all). */
  public void revokeAll(UUID userId) {
    Set<String> family = byUser.remove(userId);
    if (family != null) {
      for (String jti : family) {
        active.remove(jti);
      }
    }
  }

  public boolean isActive(String jti) {
    Session session = active.get(jti);
    return session != null && session.expiresAt().isAfter(Instant.now());
  }

  private void purgeExpired() {
    Instant now = Instant.now();
    active.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }

  public static final class UnknownRefreshTokenException extends Exception {
  }
}
