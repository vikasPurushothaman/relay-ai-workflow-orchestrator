package com.relay.bootstrap;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Resolves mode before Spring chooses a web context or constructs database beans. */
public final class LaunchEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        // Run after config data so profile and command-line conflicts are visible.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String mode = env.getProperty("RELAY_MODE");
        var profiles = Arrays.asList(env.getActiveProfiles());
        if (profiles.contains("api") || profiles.contains("worker")) {
            throw invalid("RELAY_MODE", "select modes with RELAY_MODE, not Spring profiles");
        }
        if (profiles.contains("scaffold")) {
            if (mode != null && !mode.isBlank()) {
                throw invalid("RELAY_MODE", "cannot be combined with scaffold profile");
            }
            return;
        }
        if (!"api".equals(mode) && !"worker".equals(mode)) {
            throw invalid("RELAY_MODE", "must be exactly api or worker");
        }
        String url = required(env, "RELAY_DB_URL");
        validateUrl(url);
        String user = required(env, "RELAY_DB_USER");
        String password = required(env, "RELAY_DB_PASSWORD");
        int timeout = integer(env, "RELAY_DB_TX_TIMEOUT_MS", 5000, 1000, 60000);
        int port = 8080;
        if (mode.equals("api")) {
            String seeds=env.getProperty("RELAY_LOAD_SEEDS","true");
            if(!seeds.equals("true") && !seeds.equals("false")) throw invalid("RELAY_LOAD_SEEDS","must be true or false");
            com.relay.security.ManagementCors.origins(env.getProperty("RELAY_ALLOWED_ORIGINS", "http://localhost:5173"));
            String token = required(env, "RELAY_DEMO_TOKEN");
            if (!token.matches("[A-Za-z0-9\\-._~+/]+=*")) {
                throw invalid("RELAY_DEMO_TOKEN", "must use HTTP bearer-token characters without whitespace");
            }
            port = integer(env, "RELAY_API_PORT", 8080, 1, 65535);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("relay.launch.mode", mode);
        values.put("relay.seeds.enabled",mode.equals("api") && env.getProperty("RELAY_LOAD_SEEDS","true").equals("true"));
        values.put("spring.main.web-application-type", mode.equals("api") ? "servlet" : "none");
        values.put("spring.main.keep-alive", mode.equals("worker"));
        values.put("spring.datasource.url", url);
        values.put("spring.datasource.username", user);
        values.put("spring.datasource.password", password);
        values.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        values.put("spring.datasource.hikari.connection-timeout", timeout);
        values.put("spring.datasource.hikari.validation-timeout", Math.min(1000, timeout));
        values.put("spring.datasource.hikari.initialization-fail-timeout", timeout);
        values.put("spring.datasource.hikari.maximum-pool-size", 5);
        values.put("spring.datasource.hikari.transaction-isolation", "TRANSACTION_READ_COMMITTED");
        values.put("spring.datasource.hikari.data-source-properties.connectTimeout", timeout);
        values.put("spring.datasource.hikari.data-source-properties.socketTimeout", timeout);
        values.put("spring.transaction.default-timeout", timeout + "ms");
        values.put("spring.jpa.properties.jakarta.persistence.query.timeout", timeout);
        values.put("spring.jpa.properties.jakarta.persistence.lock.timeout", timeout);
        values.put("spring.jpa.hibernate.ddl-auto", "validate");
        values.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
        values.put("spring.jpa.open-in-view", false);
        values.put("spring.sql.init.mode", "never");
        values.put("spring.flyway.url", url);
        values.put("spring.flyway.user", user);
        values.put("spring.flyway.password", password);
        values.put("spring.flyway.jdbc-properties.connectTimeout", Integer.toString(timeout));
        values.put("spring.flyway.jdbc-properties.socketTimeout", Integer.toString(timeout));
        values.put("spring.flyway.enabled", true);
        values.put("spring.flyway.clean-disabled", true);
        values.put("spring.flyway.baseline-on-migrate", false);
        values.put("spring.flyway.validate-on-migrate", true);
        values.put("spring.flyway.ignore-migration-patterns", "");
        values.put("spring.flyway.connect-retries", 0);
        values.put("server.address", "127.0.0.1");
        if (mode.equals("api")) {
            values.put("server.port", port);
        }
        // Enforce these mode invariants even over conflicting generic Spring flags.
        env.getPropertySources().addFirst(new MapPropertySource("relayLaunch", values));
    }

    private static String required(ConfigurableEnvironment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalid(key, "must be nonempty");
        }
        return value;
    }

    private static int integer(ConfigurableEnvironment env, String key, int fallback, int min, int max) {
        String value = env.getProperty(key, Integer.toString(fallback));
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(key, "must be an integer in " + min + ".." + max);
        }
    }

    private static void validateUrl(String url) {
        try {
            if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || !uri.getRawPath().matches("/[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            // Do not retain parsing causes: they may contain a credential-bearing URL.
            throw invalid("RELAY_DB_URL", "must be jdbc:mysql://host[:port]/schema without credentials or parameters");
        }
    }

    private static IllegalStateException invalid(String key, String rule) {
        return new IllegalStateException(key + " " + rule);
    }
}
