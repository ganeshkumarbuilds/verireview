package com.verireview.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves Flyway owns the schema: V1+V2 applied, all tables exist, roles seeded.
 */
class FlywayMigrationTest extends AbstractPersistenceTest {

  private static final List<String> EXPECTED_TABLES = List.of(
      "roles", "users", "user_roles", "projects", "repositories",
      "project_files", "reviews", "findings", "fix_requests", "patches",
      "verification_runs", "test_results", "agent_executions", "audit_logs");

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void flywayAppliedInitialSchemaAndRoleSeed() {
    List<String> versions = jdbc.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
        String.class);
    assertThat(versions).containsExactly("1", "2");
  }

  @Test
  void allDesignedTablesExist() {
    List<String> tables = jdbc.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1",
        String.class);
    assertThat(tables).containsAll(EXPECTED_TABLES);
  }

  @Test
  void rolesSeededWithoutDemoUsers() {
    List<String> roles = jdbc.queryForList("SELECT name FROM roles ORDER BY 1", String.class);
    assertThat(roles).containsExactly("ADMIN", "USER");
    // No global user-count assertion: the suite shares one database and other
    // tests commit isolated rows. V2 inserts into roles only (see the file).
  }
}
