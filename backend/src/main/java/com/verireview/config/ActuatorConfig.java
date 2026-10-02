package com.verireview.config;

import org.springframework.context.annotation.Configuration;

/**
 * Actuator configuration placeholder (Phase 1).
 * Endpoint exposure and health details are configured via application.yml:
 * management.endpoints.web.exposure.include
 * management.endpoint.health.show-details
 * 
 * JVM metrics are auto-bound by Spring Boot 3.x+ Micrometer integration.
 */
@Configuration
public class ActuatorConfig {
}