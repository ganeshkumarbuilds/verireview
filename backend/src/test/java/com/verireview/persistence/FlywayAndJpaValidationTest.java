package com.verireview.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Validates that all Flyway migrations (1-18) apply cleanly on a blank database
 * and that Hibernate JPA schema validation passes (ddl-auto=validate).
 * This test runs against a real PostgreSQL Testcontainer.
 * The fact that this SpringBootTest starts successfully proves JPA validation passed.
 */
class FlywayAndJpaValidationTest extends AbstractPersistenceTest {

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private EntityManager entityManager;

  @Test
  void allMigrationsApplyAndJpaValidates() {
    // 1. Verify all 18 migrations applied successfully
    var versions = jdbc.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
        String.class);

    assertThat(versions).containsExactly(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "11", "12", "13", "14", "15", "16", "17", "18");

    // 2. Verify all expected tables exist (including V17/V18 tables)
    var tables = jdbc.queryForList(
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        """,
        String.class);

    assertThat(tables).contains(
        "check_runs",
        "check_steps",
        "check_run_gates",
        "project_health_scores",
        "generation_plans",
        "generation_execution_evidence",
        "generation_revisions",
        "generation_artifacts",
        "import_jobs",
        "execution_runs");

    // 3. Verify check_run_gates has updated_at column (V18 fix)
    var checkRunGatesColumns = jdbc.queryForList(
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_name = 'check_run_gates'
        ORDER BY ordinal_position
        """,
        String.class);
    assertThat(checkRunGatesColumns).contains("created_at", "updated_at");

    // 4. Verify project_health_scores has created_at and updated_at columns (V18 fix)
    var healthScoreColumns = jdbc.queryForList(
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_name = 'project_health_scores'
        ORDER BY ordinal_position
        """,
        String.class);
    assertThat(healthScoreColumns).contains("created_at", "updated_at", "last_computed_at");

    // 5. JPA Schema Validation: the Spring context started successfully with
    // spring.jpa.hibernate.ddl-auto=validate, which means Hibernate validated
    // all entity mappings against the actual database schema.
    // Accessing the metamodel confirms entities are registered.
    Set<EntityType<?>> entities = entityManager.getMetamodel().getEntities();
    assertThat(entities).isNotEmpty();
  }

  @Test
  void checkRunGateEntityHasUpdatedAt() {
    // Direct column check for the specific V18 fix
    var hasUpdatedAt = jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_name = 'check_run_gates' AND column_name = 'updated_at'
        )
        """,
        Boolean.class);
    assertThat(hasUpdatedAt).isTrue();
  }

  @Test
  void projectHealthScoreEntityHasCreatedAndUpdatedAt() {
    // Direct column check for the specific V18 fix
    var hasCreatedAt = jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_name = 'project_health_scores' AND column_name = 'created_at'
        )
        """,
        Boolean.class);
    var hasUpdatedAt = jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_name = 'project_health_scores' AND column_name = 'updated_at'
        )
        """,
        Boolean.class);
    assertThat(hasCreatedAt).isTrue();
    assertThat(hasUpdatedAt).isTrue();
  }
}