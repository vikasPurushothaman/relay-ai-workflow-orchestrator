package com.relay.bootstrap;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.transaction.autoconfigure.TransactionProperties;
import java.time.Duration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class LaunchConfigurationTest {
    private final LaunchEnvironmentPostProcessor processor = new LaunchEnvironmentPostProcessor();

    static MockEnvironment configured(String mode) {
        return new MockEnvironment().withProperty("RELAY_MODE", mode)
                .withProperty("RELAY_DB_URL", "jdbc:mysql://localhost:3306/relay")
                .withProperty("RELAY_DB_USER", "relay")
                .withProperty("RELAY_DB_PASSWORD", "test-only-private-password")
                .withProperty("RELAY_DEMO_TOKEN", "test-only-private-token");
    }

    private void process(MockEnvironment env) {
        processor.postProcessEnvironment(env, new SpringApplication());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "API", "api,worker", "api worker", "scaffold", "unknown"})
    void rejectsMissingOrInvalidMode(String mode) {
        var env = new MockEnvironment();
        if (mode != null) env.setProperty("RELAY_MODE", mode);
        assertThatThrownBy(() -> process(env)).isInstanceOf(IllegalStateException.class)
                .hasMessage("RELAY_MODE must be exactly api or worker").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker"})
    void rejectsScaffoldMixedWithProductMode(String mode) {
        var env = configured(mode); env.setActiveProfiles("scaffold");
        assertThatThrownBy(() -> process(env)).hasMessageContaining("cannot be combined");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker"})
    void rejectsProfilesAsModeSelectors(String profile) {
        var env = configured("api"); env.setActiveProfiles(profile);
        assertThatThrownBy(() -> process(env)).hasMessageContaining("not Spring profiles");
    }

    @Test
    void scaffoldAllowsBlankTemplateModeWithoutDatabase() {
        var env = new MockEnvironment().withProperty("RELAY_MODE", ""); env.setActiveProfiles("scaffold");
        process(env);
        assertThat(env.getPropertySources().contains("relayLaunch")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELAY_DB_URL", "RELAY_DB_USER", "RELAY_DB_PASSWORD", "RELAY_DEMO_TOKEN"})
    void apiRejectsBlankRequiredSettingWithoutValueLeak(String key) {
        var env = configured("api").withProperty(key, " ");
        assertThatThrownBy(() -> process(env)).hasMessage(key + " must be nonempty").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:test", "jdbc:mysql://user:private@localhost/relay",
            "jdbc:mysql://localhost/relay?password=private", "jdbc:mysql://localhost/relay#private",
            "jdbc:mysql://localhost/", "jdbc:mysql://localhost:0/relay", "jdbc:mysql://localhost:65536/relay",
            "jdbc:mysql://localhost:bad/relay", "jdbc:mysql://localhost/relay/other", "private"})
    void rejectsAmbiguousOrCredentialBearingJdbcUrl(String url) {
        var env = configured("worker").withProperty("RELAY_DB_URL", url);
        assertThatThrownBy(() -> process(env)).hasMessageStartingWith("RELAY_DB_URL must be jdbc:mysql://")
                .hasMessageNotContaining("private").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://localhost/relay", "jdbc:mysql://127.0.0.1:1/relay",
            "jdbc:mysql://[::1]:65535/relay_test"})
    void acceptsSupportedSingleHostJdbcForms(String url) {
        var env = configured("worker").withProperty("RELAY_DB_URL", url);
        process(env);
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "1.5", "private", "9999999999999", ""})
    void rejectsInvalidApiPort(String value) {
        var env = configured("api").withProperty("RELAY_API_PORT", value);
        assertThatThrownBy(() -> process(env)).hasMessage("RELAY_API_PORT must be an integer in 1..65535").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"999", "60001", "-1", "1.5", "private", "2147483648", ""})
    void rejectsInvalidTimeout(String value) {
        var env = configured("worker").withProperty("RELAY_DB_TX_TIMEOUT_MS", value);
        assertThatThrownBy(() -> process(env)).hasMessage("RELAY_DB_TX_TIMEOUT_MS must be an integer in 1000..60000").hasNoCause();
    }

    @Test
    void sharesDatasourceAndEnforcesModeInvariantsOverGenericSpringFlags() {
        var api = configured("api"); var worker = configured("worker");
        worker.setProperty("RELAY_DEMO_TOKEN", "");
        worker.setProperty("RELAY_API_PORT", "not-used-by-worker");
        for (var env : new MockEnvironment[]{api, worker}) {
            env.getPropertySources().addFirst(new MapPropertySource("conflictingFlags", Map.of(
                    "spring.main.web-application-type", "servlet",
                    "spring.datasource.url", "jdbc:h2:mem:wrong",
                    "spring.jpa.hibernate.ddl-auto", "create-drop",
                    "spring.flyway.enabled", "false")));
            process(env);
        }
        for (String property : new String[]{"url", "username", "password", "driver-class-name",
                "hikari.connection-timeout", "hikari.transaction-isolation"}) {
            assertThat(api.getProperty("spring.datasource."+property)).isEqualTo(worker.getProperty("spring.datasource."+property));
        }
        assertThat(api.getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(worker.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(worker.getProperty("spring.main.keep-alive")).isEqualTo("true");
        assertThat(api.getProperty("spring.main.keep-alive")).isEqualTo("false");
        assertThat(worker.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(worker.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(worker.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(worker.getProperty("spring.transaction.default-timeout")).isEqualTo("5000ms");
        for (var env : new MockEnvironment[]{api, worker}) {
            assertThat(env.getProperty("spring.flyway.url")).isEqualTo(env.getProperty("spring.datasource.url"));
            assertThat(env.getProperty("spring.flyway.user")).isEqualTo(env.getProperty("spring.datasource.username"));
            assertThat(env.getProperty("spring.flyway.password")).isEqualTo(env.getProperty("spring.datasource.password"));
        }
    }

    @Test
    void bootBindsSharedDatabaseTimeoutAndStrictMigrationProperties() {
        var env = configured("worker");
        process(env);
        var binder = Binder.get(env);
        var db = binder.bind("spring.datasource", DataSourceProperties.class).get();
        var flyway = binder.bind("spring.flyway", FlywayProperties.class).get();
        var transaction = binder.bind("spring.transaction", TransactionProperties.class).get();
        assertThat(db.getUrl()).isEqualTo(flyway.getUrl());
        assertThat(db.getUsername()).isEqualTo(flyway.getUser());
        assertThat(db.getPassword()).isEqualTo(flyway.getPassword());
        assertThat(flyway.getIgnoreMigrationPatterns()).isEmpty();
        assertThat(flyway.getJdbcProperties()).containsEntry("connectTimeout", "5000").containsEntry("socketTimeout", "5000");
        assertThat(flyway.isCleanDisabled()).isTrue();
        assertThat(flyway.isBaselineOnMigrate()).isFalse();
        assertThat(transaction.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000", "60000"})
    void timeoutBoundariesAndPortBoundariesAreAccepted(String timeout) {
        for (String port : new String[]{"1", "65535"}) {
            var env = configured("api").withProperty("RELAY_DB_TX_TIMEOUT_MS", timeout).withProperty("RELAY_API_PORT",port);
            process(env);
            assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo(timeout);
            assertThat(env.getProperty("server.port")).isEqualTo(port);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"has space"," leading","line\nbreak","comma,value","é"})
    void invalidBearerConfigurationIsRejectedWithoutValueLeakage(String token) {
        var env=new org.springframework.mock.env.MockEnvironment()
            .withProperty("RELAY_MODE","api").withProperty("RELAY_DB_URL","jdbc:mysql://localhost/relay")
            .withProperty("RELAY_DB_USER","test").withProperty("RELAY_DB_PASSWORD","test")
            .withProperty("RELAY_DEMO_TOKEN",token);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LaunchEnvironmentPostProcessor()
            .postProcessEnvironment(env,new org.springframework.boot.SpringApplication()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("RELAY_DEMO_TOKEN")
            .hasMessageNotContaining(token);
    }
    @Test void seedDefaultsAndModeBoundaries() {
        var api=configured("api");process(api);
        assertThat(api.getProperty("relay.seeds.enabled")).isEqualTo("true");
        var disabled=configured("api").withProperty("RELAY_LOAD_SEEDS","false");process(disabled);
        assertThat(disabled.getProperty("relay.seeds.enabled")).isEqualTo("false");
        var worker=configured("worker").withProperty("RELAY_LOAD_SEEDS","invalid-ignored");process(worker);
        assertThat(worker.getProperty("relay.seeds.enabled")).isEqualTo("false");
    }
    @ParameterizedTest @ValueSource(strings={"","TRUE","yes","0"," true ","private-sentinel"})
    void invalidSeedSwitchRejectedWithoutEcho(String value) {
        var env=configured("api").withProperty("RELAY_LOAD_SEEDS",value);
        assertThatThrownBy(()->process(env)).hasMessage("RELAY_LOAD_SEEDS must be true or false").hasNoCause();
    }
}
