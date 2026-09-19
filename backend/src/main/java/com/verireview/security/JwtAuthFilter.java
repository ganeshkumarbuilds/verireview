package com.verireview.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless JWT authentication. A valid {@code Bearer} <em>access</em> token
 * establishes the principal; anything else (absent, tampered, expired, or a
 * refresh token presented as access) leaves the request unauthenticated and
 * the security chain answers 401/403. No sessions are created.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwt;
  private final VeriReviewUserDetailsService principals;

  public JwtAuthFilter(JwtService jwt, VeriReviewUserDetailsService principals) {
    this.jwt = jwt;
    this.principals = principals;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring("Bearer ".length()).trim();
      if (!token.isEmpty()) {
        authenticate(token);
      }
    }
    chain.doFilter(request, response);
  }

  private void authenticate(String token) {
    final JwtService.ParsedToken parsed;
    try {
      parsed = jwt.parse(token);
    } catch (JwtException | IllegalArgumentException e) {
      return;
    }
    if (!JwtService.TYPE_ACCESS.equals(parsed.type())) {
      return;
    }
    final UUID userId;
    try {
      userId = parsed.userId();
    } catch (IllegalArgumentException e) {
      return;
    }
    final UserDetails principal;
    try {
      principal = principals.loadUserByUsername(parsed.email());
    } catch (UsernameNotFoundException e) {
      return;
    }
    if (principal instanceof VeriReviewUserDetails details
        && details.getId().equals(userId)
        && details.isEnabled()) {
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(
              principal, null, principal.getAuthorities());
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
  }
}
