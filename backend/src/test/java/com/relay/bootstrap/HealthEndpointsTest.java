package com.relay.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("scaffold")
class HealthEndpointsTest {
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;
    @Autowired Validator validator;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void restoreAvailability() {
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void status(HttpResponse<String> response, int code, String status) {
        assertThat(response.statusCode()).isEqualTo(code);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo(status);
        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("json");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void livenessIsUpButDatabaseFreeScaffoldIsNotReady() throws Exception {
        status(get("/actuator/health/liveness"), 200, "UP");
        status(get("/actuator/health/readiness"), 503, "OUT_OF_SERVICE");
        status(get("/actuator/health"), 503, "OUT_OF_SERVICE");
    }

    @Test
    void brokenAndRecoveredLivenessIsReflectedWithoutRestart() throws Exception {
        AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
        status(get("/actuator/health/liveness"), 503, "DOWN");
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        status(get("/actuator/health/liveness"), 200, "UP");
    }

    @Test
    void acceptingTrafficCannotOverrideMissingDatabase() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        status(get("/actuator/health/readiness"), 503, "OUT_OF_SERVICE");
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        status(get("/actuator/health/readiness"), 503, "OUT_OF_SERVICE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workflows", "/actuator", "/actuator/env", "/actuator/info",
            "/actuator/health/db", "/actuator/health/unknown", "/login", "/missing", "/error"})
    void otherPathsAreDenied(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Location")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
    void onlyGetIsPublic(String method) throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            var response = client.send(request(path).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-real-token", "Basic dXNlcjpwYXNz"})
    void credentialsDoNotGrantAccessOrChangePublicHealth(String authorization) throws Exception {
        var denied = client.send(request("/actuator/env").header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        var health = client.send(request("/actuator/health/liveness").header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        status(health, 200, "UP");
        assertThat(health.body()).doesNotContain(authorization);
    }

    @Test
    void queryCannotEnableDetailsAndMalformedBodyPreservesBadRequest() throws Exception {
        status(get("/actuator/health?showDetails=always&token=not-a-secret"), 503, "OUT_OF_SERVICE");
        var response = client.send(request("/actuator/health/liveness")
                .header("Content-Type", "application/json")
                .method("GET", HttpRequest.BodyPublishers.ofString("{malformed")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = json.readTree(response.body());
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.has("message")).isFalse();
        assertThat(error.has("trace")).isFalse();
        assertThat(response.body()).doesNotContain("malformed");
    }

    @Test
    void repeatedAndConcurrentReadsHaveNoSessionOrStateMutation() {
        var requests = java.util.stream.IntStream.range(0, 8).mapToObj(i -> client.sendAsync(
                request("/actuator/health/readiness").GET().build(), HttpResponse.BodyHandlers.ofString())).toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        requests.forEach(future -> status(future.join(), 503, "OUT_OF_SERVICE"));
    }

    @Test
    void scaffoldDoesNotCreateDatabaseServicesAndValidationIsAvailable() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(SeedStartup.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(validator.validate(new ValidationProbe(""))).hasSize(1);
        assertThat(validator.validate(new ValidationProbe("relay"))).isEmpty();
    }

    record ValidationProbe(@NotBlank String value) { }
}
