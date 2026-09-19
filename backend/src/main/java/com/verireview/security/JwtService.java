package com.verireview.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and validates HS256 JWTs (ADR-009). The signing key comes from the
 * {@code JWT_SIGNING_KEY} environment variable only; startup fails fast when
 * it is missing or shorter than 256 bits. Exactly one algorithm (HS256) is
 * ever accepted — the parser is pinned to the signing key.
 */
@Service
public class JwtService {

  public static final String TYPE_ACCESS = "access";
  public static final String TYPE_REFRESH = "refresh";

  private final String secret;
  private final Duration accessTtl;
  private final Duration refreshTtl;
  private SecretKey key;

  public JwtService(
      @Value("${app.jwt.secret:}") String secret,
      @Value("${app.jwt.access-minutes:15}") long accessMinutes,
      @Value("${app.jwt.refresh-days:7}") long refreshDays) {
    this.secret = secret;
    this.accessTtl = Duration.ofMinutes(accessMinutes);
    this.refreshTtl = Duration.ofDays(refreshDays);
  }

  @PostConstruct
  void validateKey() {
    byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
    if (raw.length < 32) {
      throw new IllegalStateException(
          "JWT signing key is missing or too short: set JWT_SIGNING_KEY "
              + "to at least 256 bits (32 bytes)");
    }
    this.key = Keys.hmacShaKeyFor(raw);
  }

  /** Parsed, signature- and expiry-verified token claims. */
  public record ParsedToken(
      UUID userId, String email, Set<String> roles, String type, String jti) {
  }

  public String issueAccess(UUID userId, String email, Set<String> roles) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("email", email)
        .claim("roles", roles.stream().sorted().toList())
        .claim("type", TYPE_ACCESS)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTtl)))
        .signWith(key)
        .compact();
  }

  public IssuedRefresh issueRefresh(UUID userId, String email) {
    Instant now = Instant.now();
    String jti = UUID.randomUUID().toString();
    Instant expiresAt = now.plus(refreshTtl);
    String token = Jwts.builder()
        .subject(userId.toString())
        .claim("email", email)
        .claim("type", TYPE_REFRESH)
        .id(jti)
        .issuedAt(Date.from(now))
        .expiration(Date.from(expiresAt))
        .signWith(key)
        .compact();
    return new IssuedRefresh(token, jti, expiresAt);
  }

  public record IssuedRefresh(String token, String jti, Instant expiresAt) {
  }

  /**
   * Verifies signature + expiry and returns the claims.
   *
   * @throws JwtException when the token is malformed, tampered, or expired
   */
  public ParsedToken parse(String token) throws JwtException {
    Claims claims = Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token)
        .getPayload();
    Set<String> roles = new HashSet<>();
    Object rawRoles = claims.get("roles");
    if (rawRoles instanceof Iterable<?> iterable) {
      for (Object role : iterable) {
        roles.add(String.valueOf(role));
      }
    }
    Object typeClaim = claims.get("type");
    return new ParsedToken(
        UUID.fromString(claims.getSubject()),
        claims.get("email", String.class),
        roles,
        typeClaim == null ? null : String.valueOf(typeClaim),
        claims.getId());
  }

  public long accessExpiresInSeconds() {
    return accessTtl.toSeconds();
  }
}
