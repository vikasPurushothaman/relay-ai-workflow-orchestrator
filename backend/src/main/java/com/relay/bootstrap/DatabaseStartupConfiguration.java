package com.relay.bootstrap;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "relay.launch.mode")
public class DatabaseStartupConfiguration {
    @Bean
    @ConditionalOnProperty(name = "relay.launch.mode", havingValue = "api")
    FlywayMigrationStrategy apiMigrations() {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("API database migration failed; verify database access and migration history");
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "relay.launch.mode", havingValue = "worker")
    FlywayMigrationStrategy workerSchemaValidation() {
        return DatabaseStartupConfiguration::validateWorkerSchema;
    }

    static void validateWorkerSchema(Flyway flyway) {
        try {
            flyway.validate();
            var info = flyway.info();
            if (info.current() == null || info.pending().length != 0) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Worker database schema is unavailable or incompatible; start the matching API migrations first");
        }
    }
}
