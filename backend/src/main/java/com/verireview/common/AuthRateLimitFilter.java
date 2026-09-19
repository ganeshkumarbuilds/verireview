package com.verireview.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In-memory fixed-window rate limiter for the strictest tier
 * ({@code /api/v1/auth/*}, SECURITY_DESIGN §8). Single-instance Phase 3;
 * the Redis migration trigger stays as defined in {@code TECH_STACK.md}.
 * Exceeding the window answers {@code 429 + Retry-After}.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

  static final String AUTH_PREFIX = "/api/v1/auth/";
  static final long WINDOW_SECONDS = 60;

  private record Window(Instant startedAt, AtomicInteger count) {
  }

  private final int maxPerMinute;
  private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

  public AuthRateLimitFilter(@Value("${app.auth.rate-limit-per-minute:100}") int maxPerMinute) {
    this.maxPerMinute = maxPerMinute;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().startsWith(AUTH_PREFIX) && !allow(clientKey(request))) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
      return;
    }
    chain.doFilter(request, response);
  }

  boolean allow(String key) {
    Instant now = Instant.now();
    Window window =
        windows.compute(
            key,
            (ignored, current) -> {
              if (current == null
                  || now.isAfter(current.startedAt().plusSeconds(WINDOW_SECONDS))) {
                return new Window(now, new AtomicInteger(0));
              }
              return current;
            });
    return window.count().incrementAndGet() <= maxPerMinute;
  }

  private static String clientKey(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    String ip = forwarded != null && !forwarded.isBlank()
        ? forwarded.split(",")[0].trim()
        : request.getRemoteAddr();
    return ip + "|" + AUTH_PREFIX;
  }
}
