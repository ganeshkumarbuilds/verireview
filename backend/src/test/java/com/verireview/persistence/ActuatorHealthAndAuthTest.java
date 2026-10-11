package com.verireview.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies that /actuator/health is publicly accessible (no authentication required)
 * and that protected endpoints require authentication.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthAndAuthTest {

  static final PostgreSQLContainer POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer("postgres:16");
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("app.jwt.secret", () -> "test-only-phase3-signing-key-not-a-real-secret-0123456789abcdef");
  }

  @Value("${local.server.port}")
  private int port;

  private final RestTemplate restTemplate = new RestTemplate();

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @Test
  void actuatorHealthIsPubliclyAccessible() {
    ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("UP");
    assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
  }

  @Test
  void actuatorHealthReturnsStatusUp() {
    ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void protectedEndpointRequiresAuthentication() {
    // Protected endpoint should return 401 without token
    try {
      restTemplate.getForEntity(baseUrl() + "/api/v1/dashboard/summary", String.class);
    } catch (HttpClientErrorException e) {
      assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      return;
    }
    assertThat(false).as("Expected 401 Unauthorized").isTrue();
  }

  @Test
  void authEndpointIsPublic() {
    // /api/v1/auth/login should be publicly accessible (any response other than 401 means it's public)
    try {
      restTemplate.postForEntity(baseUrl() + "/api/v1/auth/login",
          "{\"email\":\"test@example.com\",\"password\":\"password123\"}", String.class);
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      // 400 = bad credentials (public endpoint validates input)
      // 401 = would mean auth filter rejected before validation
      assertThat(e.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
      return;
    } catch (org.springframework.web.client.HttpServerErrorException e) {
      // 500 = server error (user not found, but still public - not 401)
      assertThat(e.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
      return;
    }
    // 200 or other = also public
    assertThat(true).isTrue();
  }
}