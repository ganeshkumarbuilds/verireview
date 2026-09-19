package com.verireview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Phase 5: allows the Vite dev server to call {@code /api/**} from a browser.
 * Origins come from the environment (default: local dev server only); methods
 * are limited to what the API exposes. Credentials are not used (JWT travels
 * in the Authorization header). Production origins must be set explicitly.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  public WebConfig(
      @Value("${app.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .maxAge(3600);
  }

  /**
   * Same policy for the Spring Security chain. Without this, preflight
   * (OPTIONS) requests — which carry no Authorization header — are rejected
   * by the security filter before CORS headers are applied, and browsers
   * report the authenticated call as a CORS failure.
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    for (String origin : allowedOrigins) {
      configuration.addAllowedOrigin(origin);
    }
    configuration.addAllowedMethod("GET");
    configuration.addAllowedMethod("POST");
    configuration.addAllowedMethod("PATCH");
    configuration.addAllowedMethod("DELETE");
    configuration.addAllowedMethod("OPTIONS");
    configuration.addAllowedHeader("*");
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }
}
