package com.verireview.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that the current Flyway schema is fully applied and the seeded
 * roles required by authentication are present.
 */
class FlywayMigrationTest extends AbstractPersistenceTest {

  private static final List<String> EXPECTED_TABLES = List.of(
      "roles", "users", "user_roles", "projects", "repositories",
      "project_files", "reviews", "findings", "fix_requests", "patches",
      "verification_runs", "test_results", "agent_executions", "audit_logs");

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void flywayAppliedAllCurrentMigrations() {
    List<String> versions = jdbc.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
        String.class);

    assertThat(versions).containsExactly(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");
  }

  @Test
  void coreTablesExist() {
    List<String> tables = jdbc.queryForList(
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        """,
        String.class);

    assertThat(tables).containsAll(EXPECTED_TABLES);
  }

  @Test
  void authenticationRolesAreSeeded() {
    List<String> roles = jdbc.queryForList(
        "SELECT name FROM roles ORDER BY name",
        String.class);

    assertThat(roles).contains("USER", "ADMIN");
  }
}
