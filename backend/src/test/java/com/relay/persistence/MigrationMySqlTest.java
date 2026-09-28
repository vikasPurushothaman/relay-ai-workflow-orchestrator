package com.relay.persistence;

import com.relay.RelayApplication;
import com.relay.testing.MySqlIntegrationSupport;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class MigrationMySqlTest extends MySqlIntegrationSupport {
    @Test void migrationsAreRepeatableAndWorkersNeverRepairIncompatibleHistory() throws Exception {
        var app = new SpringApplication(RelayApplication.class);
        app.setRegisterShutdownHook(false);
        assertThatThrownBy(() -> start(app, "--RELAY_MODE=worker"))
            .hasRootCauseMessage("Worker database schema is unavailable or incompatible; start the matching API migrations first");
        // Failed worker startup must not create any tables.
        try (var connection = DriverManager.getConnection(setting("RELAY_DB_URL"), setting("RELAY_DB_USER"), setting("RELAY_DB_PASSWORD"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='relay'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
        java.util.List<java.util.Map<String, Object>> history;
        try (var context = start(app)) {
            var sql = context.getBean(JdbcTemplate.class);
            history = sql.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            assertThat(history).hasSize(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='1'", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='relay'", String.class))
                .containsExactlyInAnyOrder("flyway_schema_history", "workflows", "runs", "steps", "step_attempts", "approvals", "queue_jobs");
        }
        for (String mode : new String[]{"api", "worker"}) {
            try (var context = start(app, "--RELAY_MODE=" + mode)) {
                var sql = context.getBean(JdbcTemplate.class);
                assertThat(sql.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history);
                assertThat(context.getBean(WorkflowRepository.class).count()).isZero();
            }
        }
        // Corruption is confined to this disposable container; neither launch mode may repair it.
        try (var connection = DriverManager.getConnection(setting("RELAY_DB_URL"), setting("RELAY_DB_USER"), setting("RELAY_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'")).isEqualTo(1);
            assertThatThrownBy(() -> start(app)).hasRootCauseMessage("API database migration failed; verify database access and migration history");
            assertThatThrownBy(() -> start(app, "--RELAY_MODE=worker"))
                .hasRootCauseMessage("Worker database schema is unavailable or incompatible; start the matching API migrations first");
            try (var rows = statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='1'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }
}
