package com.verireview.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness probe (Phase 1). Intentionally thin: no service layer,
 * no business logic. Richer health/observability arrives in later phases.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

  @GetMapping
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
