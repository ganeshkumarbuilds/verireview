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
      "verification_runs", "test_results", "agent_executions", "audit_logs",
      "generation_fix_approvals", "generation_file_versions", "analysis_checks",
      "analysis_file_results");

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void flywayAppliedAllCurrentMigrations() {
    List<String> versions = jdbc.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
        String.class);

    assertThat(versions).containsExactly(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21",
        "22", "23", "24", "25", "26", "27", "28", "29", "30");
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

  @Test
  void stepBColumnsAndTablesExist() {
    Integer fileResults = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = 'public' AND table_name = 'analysis_file_results'",
        Integer.class);
    Integer categoryConstraint = jdbc.queryForObject(
        "SELECT COUNT(*) FROM pg_constraint "
            + "WHERE conname = 'ck_findings_category'",
        Integer.class);
    assertThat(fileResults).isEqualTo(1);
    assertThat(categoryConstraint).isEqualTo(1);
  }

  @Test
  void reviewFixRoundColumnAndConstraintExist() {
    Integer column = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = 'reviews' "
            + "AND column_name = 'fix_round'", Integer.class);
    Integer constraint = jdbc.queryForObject(
        "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'ck_reviews_fix_round'",
        Integer.class);
    assertThat(column).isEqualTo(1);
    assertThat(constraint).isEqualTo(1);
  }

  @Test
  void blankDbDdlAutoValidateSucceeds() {
    // When AbstractPersistenceTest starts, Flyway runs all migrations on the fresh blank DB.
    // Spring Boot Hibernate's ddl-auto=validate validates all @Entity mappings against the migrated schema.
    // Reaching this test proves that ddl-auto=validate succeeded without any schema mismatch.
    Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE fix_round >= 0 AND fix_round <= 5", Integer.class);
    assertThat(result).isNotNull();
  }
}
