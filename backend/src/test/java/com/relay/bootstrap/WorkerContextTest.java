package com.relay.bootstrap;

import com.relay.RelayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;

class WorkerContextTest {
    @Test
    void actualWorkerBootstrapCreatesNoWebContextOrSecurityDespiteServletOverride() {
        SpringApplication app = new SpringApplication(RelayApplication.class, TestDatabaseHealth.class);
        app.setRegisterShutdownHook(false);
        // Test-only exclusions: tests context shape, not database integration or startup gate.
        try (var context = app.run("--RELAY_MODE=worker", "--RELAY_LOAD_SEEDS=true", "--RELAY_DB_URL=jdbc:mysql://localhost/relay",
                "--RELAY_DB_USER=test", "--RELAY_DB_PASSWORD=test-only",
                "--spring.main.web-application-type=servlet",
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")) {
            assertThat(context).isNotInstanceOf(WebApplicationContext.class);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(context.getBeansOfType(SeedStartup.class)).isEmpty();
            assertThat(context.getBeansOfType(com.relay.api.SeedLoader.class)).isEmpty();
            assertThat(context.getBeansOfType(HealthSecurityConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(FlywayMigrationStrategy.class)).containsOnlyKeys("workerSchemaValidation");
            assertThat(context.getEnvironment().getProperty("spring.main.keep-alive")).isEqualTo("true");
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestDatabaseHealth {
        @Bean org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(){return org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);}
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(){return org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);}

        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.outOfService().build();
        }
    }
}
