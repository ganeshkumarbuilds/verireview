package com.verireview.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts or generates a trace ID (X-Request-ID / X-Correlation-ID) and pushes it into MDC for
 * structured logging. The ID is also added to the response header so clients can correlate.
 *
 * <p>Runs at {@code Ordered.HIGHEST_PRECEDENCE} so the trace ID is available for all downstream
 * filters and controllers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  static final String TRACE_ID_HEADER = "X-Request-ID";
  static final String MDC_KEY = "traceId";
  private static final InheritableThreadLocal<String> CURRENT_TRACE_ID = new InheritableThreadLocal<>();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = request.getHeader(TRACE_ID_HEADER);
    if (traceId == null || traceId.isBlank()) {
      traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    CURRENT_TRACE_ID.set(traceId);
    MDC.put(MDC_KEY, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
      CURRENT_TRACE_ID.remove();
    }
  }

  /** Returns the current request's trace ID, or {@code null} if outside a filtered request. */
  public static String currentTraceId() {
    return CURRENT_TRACE_ID.get();
  }
}