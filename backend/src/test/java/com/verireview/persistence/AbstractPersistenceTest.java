package com.verireview.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared real-PostgreSQL (16) foundation for persistence-backed tests. One
 * container for the whole suite (image matches docker-compose.yml), wired via
 * {@code @DynamicPropertySource} so every subclass shares both the container
 * and Spring's cached test context. No H2 anywhere.
 *
 * <p>Also supplies the Phase 3 JWT signing key as a fixture credential via
 * {@code @DynamicPropertySource} (highest precedence, test scope only) so the
 * main {@code application.yml} stays fully in effect. Production reads
 * {@code JWT_SIGNING_KEY} from the environment.
 */
@SpringBootTest
public abstract class AbstractPersistenceTest {

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
    registry.add(
        "app.jwt.secret",
        () -> "test-only-phase3-signing-key-not-a-real-secret-0123456789abcdef");
  }
}
