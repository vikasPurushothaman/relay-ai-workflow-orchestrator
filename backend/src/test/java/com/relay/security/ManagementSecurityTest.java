package com.relay.security;

import com.relay.RelayApplication;
import com.relay.api.ApiErrorAdvice;
import com.relay.persistence.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;

class ManagementSecurityTest {
    static ConfigurableApplicationContext context;
    static int port;
    static final String TOKEN="test-management-token";
    static final HttpClient client=HttpClient.newHttpClient();
    @BeforeAll static void start() throws Exception {
        try (var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        context=new SpringApplication(RelayApplication.class,Fixture.class).run(
            "--RELAY_MODE=api","--RELAY_LOAD_SEEDS=false","--RELAY_DEMO_TOKEN="+TOKEN,"--RELAY_API_PORT="+port,
            "--RELAY_DB_URL=jdbc:mysql://localhost/unused","--RELAY_DB_USER=test","--RELAY_DB_PASSWORD=test",
            "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
    }
    @AfterAll static void stop() { if(context!=null) context.close(); }
    @TestConfiguration static class Fixture {
        @Bean org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(){return org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);}
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(){return org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);}

        @Bean HealthIndicator dbHealthIndicator(){return () -> Health.outOfService().build();}
        @Bean RunRepository runs(){return org.mockito.Mockito.mock(RunRepository.class);}
        @Bean QueueJobRepository jobs(){return org.mockito.Mockito.mock(QueueJobRepository.class);}
        @Bean WorkflowRepository workflows(){return org.mockito.Mockito.mock(WorkflowRepository.class);}
        @Bean Probe probe(){return new Probe();}
    }
    @RestController static class Probe {
        @GetMapping("/workflows/forbidden") Object forbidden() { throw new org.springframework.security.access.AccessDeniedException("private-sentinel"); }
        @RequestMapping({"/workflows/auth-probe","/runs","/approvals/auth-probe","/approvals/a/auth-probe"})
        Object protectedRoute(){
            var a=new Approval("a","r",1L,"node","Review",ApprovalStatus.pending,Instant.EPOCH);
            a.recordHumanDecision(true,Instant.parse("2026-09-25T00:00:00Z"));
            return java.util.Map.of("actor",a.getDecidedBy(),"status",a.getStatus().name());
        }
    }
    HttpResponse<String> call(String method,String path,String... headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).method(method,HttpRequest.BodyPublishers.noBody());
        if(headers.length>0)request.headers(headers);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(strings={"/workflows/auth-probe","/runs","/approvals/auth-probe","/approvals/a/auth-probe"})
    void validTokenAndDecisionActor(String path) throws Exception {
        var response=call("POST",path,"Authorization","Bearer "+TOKEN,"X-Decided-By","forged-person");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("demo-operator","approved").doesNotContain(TOKEN,"forged-person");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"Bearer wrong","Bearer","Basic dGVzdA==","Bearer test-management-token, Bearer wrong","Bearer test-management-token extra"})
    void invalidCredentials(String value) throws Exception {
        var response=call("GET","/workflows/auth-probe","Authorization",value);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("unauthorized").doesNotContain(value);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"Relay\"");
    }
    @Test void noQueryCookieOrDuplicateHeaderAuthentication() throws Exception {
        for(var response:List.of(call("GET","/workflows?token="+TOKEN),call("GET","/workflows/auth-probe","Cookie","token="+TOKEN),
                call("GET","/workflows/auth-probe","Authorization","Bearer "+TOKEN,"Authorization","Bearer "+TOKEN))) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain(TOKEN);
        }
    }
    @Test void publicHealthAndDeniedHooksAndActuator() throws Exception {
        assertThat(call("GET","/actuator/health/liveness","Authorization","Bearer wrong").statusCode()).isEqualTo(200);
        for(String path:List.of("/hooks/wf","/actuator/env","/login","/workflows-extra"))
            assertThat(call("GET",path,"Authorization","Bearer "+TOKEN).statusCode()).isEqualTo(401);
        assertThat(call("GET","/workflows/missing","Authorization","Bearer "+TOKEN).statusCode()).isEqualTo(404);
    }
    @Test void authenticatedAccessFailureHasSafe403() throws Exception {
        var response=call("GET","/workflows/forbidden","Authorization","Bearer "+TOKEN);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("forbidden").doesNotContain("private-sentinel",TOKEN);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void identityDoesNotLeakBetweenRequests() throws Exception {
        var futures=new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for(int i=0;i<12;i++){
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/runs"));
            if(i%2==0)b.header("Authorization","bEaReR "+TOKEN);
            futures.add(client.sendAsync(b.build(),HttpResponse.BodyHandlers.ofString()));
        }
        for(int i=0;i<12;i++)assertThat(futures.get(i).join().statusCode()).isEqualTo(i%2==0?200:401);
        assertThat(call("GET","/runs").statusCode()).isEqualTo(401);
    }
    @Test void allowedCorsPreflightAndReadableUnauthorized() throws Exception {
        var preflight=call("OPTIONS","/approvals/a/approve","Origin","http://localhost:5173",
            "Access-Control-Request-Method","POST","Access-Control-Request-Headers","authorization,content-type");
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        var denied=call("GET","/workflows/auth-probe","Origin","http://localhost:5173");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        var allowed=call("GET","/workflows/auth-probe","Origin","http://localhost:5173","Authorization","Bearer "+TOKEN);
        assertThat(allowed.statusCode()).isEqualTo(200);
        var forbidden=call("GET","/workflows/forbidden","Origin","http://localhost:5173","Authorization","Bearer "+TOKEN);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(forbidden.body()).contains("forbidden");
    }
    @Test void deniedCorsOriginMethodAndHeaders() throws Exception {
        for(var response:List.of(
            call("OPTIONS","/workflows/auth-probe","Origin","http://evil.test","Access-Control-Request-Method","GET"),
            call("OPTIONS","/workflows/auth-probe","Origin","null","Access-Control-Request-Method","GET"),
            call("OPTIONS","/workflows/auth-probe","Origin","http://localhost:5173","Access-Control-Request-Method","TRACE"),
            call("OPTIONS","/workflows/auth-probe","Origin","http://localhost:5173","Access-Control-Request-Method","POST","Access-Control-Request-Headers","x-decided-by"),
            call("GET","/workflows/auth-probe","Origin","http://evil.test","Authorization","Bearer "+TOKEN))) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("cors_denied").doesNotContain(TOKEN);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }
}
