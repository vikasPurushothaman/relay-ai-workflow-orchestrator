package com.relay.testing;

import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;

/** Sequential test-class lifecycle; Compose tasks retain their supplied environment. */
public abstract class MySqlIntegrationSupport {
    private static MySQLContainer mysql;
    private static final Map<String, String> settings = new LinkedHashMap<>();
    private static final String IMAGE = "mysql@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d";

    @BeforeAll
    static void provision(TestInfo info) throws Exception {
        if (!Boolean.getBoolean("relay.testcontainers")) return;
        mysql = new MySQLContainer(IMAGE).withDatabaseName("relay")
            .withUsername("relay").withPassword(UUID.randomUUID().toString());
        try {
            mysql.start();
            settings.put("RELAY_MODE", "api");
            // Bootstrap deliberately forbids JDBC query strings.
            settings.put("RELAY_DB_URL", "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/relay");
            settings.put("RELAY_DB_USER", mysql.getUsername());
            settings.put("RELAY_DB_PASSWORD", mysql.getPassword());
            settings.put("RELAY_DEMO_TOKEN", UUID.randomUUID().toString());
            try (var socket = new ServerSocket(0)) {
                settings.put("RELAY_API_PORT", Integer.toString(socket.getLocalPort()));
            }
            if (!info.getTestClass().orElseThrow().getSimpleName().equals("SeedMySqlTest")) {
                settings.put("RELAY_LOAD_SEEDS", "false");
            }
        } catch (Exception | Error failure) {
            release();
            throw failure;
        }
    }

    @AfterAll
    static void release() {
        try { if (mysql != null) mysql.close(); }
        finally { mysql = null; settings.clear(); }
    }

    protected static String setting(String key) {
        return settings.containsKey(key) ? settings.get(key) : System.getenv(key);
    }

    protected static ConfigurableApplicationContext start(SpringApplication app, String... overrides) {
        var args = new LinkedHashMap<>(settings);
        for (String arg : overrides) {
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals < 3) throw new IllegalArgumentException("Expected --key=value");
            args.put(arg.substring(2, equals), arg.substring(equals + 1));
        }
        return app.run(args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }
}
