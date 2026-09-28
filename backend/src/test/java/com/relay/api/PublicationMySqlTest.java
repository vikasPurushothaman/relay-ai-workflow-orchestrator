package com.relay.api;

import com.relay.RelayApplication;
import com.relay.persistence.*;
import com.relay.workflow.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class PublicationMySqlTest extends com.relay.testing.MySqlIntegrationSupport {
    static final AtomicBoolean block=new AtomicBoolean();
    static CountDownLatch validated=new CountDownLatch(1), proceed=new CountDownLatch(1);
    @TestConfiguration static class ControlledValidation {
        @Bean @Primary PublishValidator gatedValidator() {
            var actual=new PublishValidator(new NodeCatalog(),new OutputSchemaValidator());
            var mock=org.mockito.Mockito.mock(PublishValidator.class);
            org.mockito.Mockito.doAnswer(invocation->{
                WorkflowDefinition value=invocation.getArgument(0);actual.validate(value);
                if(value.id().equals("race") && block.compareAndSet(true,false)) {
                    validated.countDown();
                    if(!proceed.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
                }
                return null;
            }).when(mock).validate(org.mockito.ArgumentMatchers.any());
            return mock;
        }
    }
    final JsonMapper json=new JsonMapper();
    final HttpClient client=HttpClient.newHttpClient();
    final String base="http://127.0.0.1:"+setting("RELAY_API_PORT");
    String definition(String id,String name,String secret) {
        return """
            {"id":"%s","name":"%s","trigger":{"type":"webhook","secret":"%s"},"entry":"a","limits":{"max_steps":5},"nodes":[{"id":"a","type":"delay","params":{"seconds":0},"next":"a"}]}
            """.formatted(id,name,secret);
    }
    HttpRequest request(String method,String path,String body,boolean auth) {
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(30));
        if(auth)b.header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN"));
        if(body!=null)b.header("Content-Type","application/json");
        return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> call(String method,String path,String body) throws Exception {
        return client.send(request(method,path,body,true),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode response(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("secret-v1","secret-v2",setting("RELAY_DEMO_TOKEN"));
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return json.readTree(response.body());
    }
    @Test void publicationRevisionFencingAndSnapshotsSurviveEditsAndRestart() throws Exception {
        assertThat(setting("RELAY_DB_URL")).isNotBlank();
        var app=new SpringApplication(RelayApplication.class,ControlledValidation.class);app.setRegisterShutdownHook(false);
        try(var context=start(app)) {
            var repo=context.getBean(WorkflowRepository.class);
            var runs=context.getBean(RunRepository.class);
            var factory=context.getBean(RunSnapshotFactory.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var policy=json.readTree("{\"policy_version\":1}"); // Deliberately minimal fixture, not a production execution policy.
            ObjectNode input=json.createObjectNode().put("message","original");
            response(call("POST","/workflows",definition("wf","First","secret-v1")),201);
            assertThatThrownBy(()->factory.prepare("r","wf",policy,input,TriggerType.manual,Instant.now()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThatThrownBy(()->tx.execute(s->factory.prepare("r","wf",policy,input,TriggerType.manual,Instant.now())))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.reason()).isEqualTo(ApiFailure.Reason.NOT_PUBLISHED));
            response(client.send(request("POST","/workflows/wf/publish",null,false),HttpResponse.BodyHandlers.ofString()),401);
            assertThat(repo.findById("wf").orElseThrow().getPublishedDefinition()).isNull();
            response(call("POST","/workflows/missing/publish",null),404);
            response(call("POST","/workflows/wf/publish","{}"),400);
            response(call("POST","/workflows/wf/publish","{"),400);
            var crossOrigin=client.send(HttpRequest.newBuilder(URI.create(base+"/workflows/wf/publish"))
                .header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN"))
                .header("Origin","http://localhost:5173").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(crossOrigin.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
            var published=response(crossOrigin,200);
            response(call("GET","/workflows/wf/publish",null),405);
            assertThat(published.get("status").asString()).isEqualTo("published");
            assertThat(published.get("published_definition").get("trigger").has("secret")).isFalse();
            assertThat(published.get("published_secret_configured").asBoolean()).isTrue();
            var original=repo.findById("wf").orElseThrow();
            var revision=original.getRevision();var at=original.getPublishedAt();
            response(call("POST","/workflows/wf/publish",null),200);
            assertThat(repo.findById("wf").orElseThrow().getRevision()).isEqualTo(revision);
            assertThat(repo.findById("wf").orElseThrow().getPublishedAt()).isEqualTo(at);
            tx.executeWithoutResult(s -> runs.saveAndFlush(factory.prepare("r1","wf",policy,input,TriggerType.manual,Instant.now())));
            input.put("message","changed");((ObjectNode)policy).put("policy_version",2);
            var first=runs.findById("r1").orElseThrow();
            assertThat(json.readTree(first.getDefinitionSnapshot())).isEqualTo(json.readTree(original.getPublishedDefinition()));
            assertThat(first.getInput()).contains("original").doesNotContain("changed");
            assertThat(json.readTree(first.getExecutionPolicy()).get("policy_version").asInt()).isEqualTo(1);
            assertThat(first.getCurrentNodeId()).isEqualTo("a");
            assertThat(first.getStepsExecuted()).isZero();assertThat(first.getNextStepSequence()).isEqualTo(1);
            var edited=response(call("PUT","/workflows/wf",definition("wf","Second","secret-v2")),200);
            assertThat(edited.get("status").asString()).isEqualTo("draft");
            assertThat(edited.get("published_definition").get("name").asString()).isEqualTo("First");
            assertThatThrownBy(()->tx.execute(s->factory.prepare("blocked","wf",policy,input,TriggerType.manual,Instant.now())))
                .isInstanceOf(ApiFailure.class);
            response(call("POST","/workflows/wf/publish",null),200);
            tx.executeWithoutResult(s -> runs.saveAndFlush(factory.prepare("r2","wf",policy,json.nullNode(),TriggerType.webhook,Instant.now())));
            assertThat(runs.findById("r1").orElseThrow().getDefinitionSnapshot()).contains("First","secret-v1").doesNotContain("secret-v2");
            assertThat(runs.findById("r2").orElseThrow().getDefinitionSnapshot()).contains("Second","secret-v2");
            assertThat(runs.findById("r2").orElseThrow().getInput()).isEqualTo("null");
            // Rollback removes the prepared/persisted run. No acceptance/queue is exposed yet.
            tx.executeWithoutResult(s->{runs.saveAndFlush(factory.prepare("rollback","wf",policy,input,TriggerType.manual,Instant.now()));s.setRollbackOnly();});
            assertThat(runs.findById("rollback")).isEmpty();
            tx.executeWithoutResult(s->{
                var r=runs.findById("r1").orElseThrow();
                ReflectionTestUtils.setField(r,"definitionSnapshot","{}");
                ReflectionTestUtils.setField(r,"input","{}");
                ReflectionTestUtils.setField(r,"executionPolicy","{}");
                runs.flush();
            });
            assertThat(runs.findById("r1").orElseThrow().getDefinitionSnapshot()).contains("First","secret-v1");
            assertThat(runs.findById("r1").orElseThrow().getInput()).contains("original");
            assertThat(json.readTree(runs.findById("r1").orElseThrow().getExecutionPolicy()).get("policy_version").asInt()).isEqualTo(1);
            // Every failed publish retains the previous frozen publication and leaves the edited draft unchanged.
            for(var bad:Map.of("invalid_node_type",definition("wf","Invalid","secret-v2").replace("delay","teleport"),
                    "missing_param",definition("wf","Invalid","secret-v2").replace("\"seconds\":0",""),
                    "invalid_edge_target",definition("wf","Invalid","secret-v2").replace("\"next\":\"a\"","\"next\":\"missing\""),
                    "invalid_entry",definition("wf","Invalid","secret-v2").replace("\"entry\":\"a\"","\"entry\":\"missing\"" )).entrySet()) {
                response(call("PUT","/workflows/wf",bad.getValue()),200);
                var before=repo.findById("wf").orElseThrow();
                assertThat(response(call("POST","/workflows/wf/publish",null),400).get("error").get("code").asString()).isEqualTo(bad.getKey());
                var after=repo.findById("wf").orElseThrow();
                assertThat(after.getRevision()).isEqualTo(before.getRevision());
                assertThat(after.getPublishedDefinition()).isEqualTo(before.getPublishedDefinition());
                assertThat(after.getPublishedAt()).isEqualTo(before.getPublishedAt());
                assertThat(after.getStatus()).isEqualTo(WorkflowStatus.draft);
            }
            // Force an edit after validation but before the publication write.
            response(call("POST","/workflows",definition("race","Old","secret-v1")),201);
            block.set(true);
            var future=client.sendAsync(request("POST","/workflows/race/publish",null,true),HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(validated.await(15,TimeUnit.SECONDS)).isTrue();
                response(call("PUT","/workflows/race",definition("race","New","secret-v2")),200);
            } finally {proceed.countDown();}
            response(future.get(20,TimeUnit.SECONDS),409);
            assertThat(repo.findById("race").orElseThrow().getPublishedDefinition()).isNull();
            response(call("POST","/workflows/race/publish",null),200);
            assertThat(repo.findById("race").orElseThrow().getPublishedDefinition()).contains("New","secret-v2");
            // A snapshot transaction keeps the same row lock used by edits through commit.
            var captured=new CountDownLatch(1);var release=new CountDownLatch(1);
            var snapshot=CompletableFuture.runAsync(()->tx.executeWithoutResult(s->{
                var run=factory.prepare("race-run","race",policy,input,TriggerType.manual,Instant.now());
                captured.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                runs.saveAndFlush(run);
            }));
            CompletableFuture<HttpResponse<String>> edit;
            try {
                assertThat(captured.await(15,TimeUnit.SECONDS)).isTrue();
                edit=client.sendAsync(request("PUT","/workflows/race",definition("race","After capture","secret-v1"),true),HttpResponse.BodyHandlers.ofString());
                assertThatThrownBy(()->edit.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {release.countDown();}
            snapshot.get(20,TimeUnit.SECONDS);response(edit.get(20,TimeUnit.SECONDS),200);
            assertThat(runs.findById("race-run").orElseThrow().getDefinitionSnapshot()).contains("New","secret-v2").doesNotContain("After capture");
            assertThat(repo.findById("race").orElseThrow().getStatus()).isEqualTo(WorkflowStatus.draft);
            assertThatThrownBy(()->tx.execute(s->factory.prepare("missing-run","missing",policy,input,TriggerType.manual,Instant.now())))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.reason()).isEqualTo(ApiFailure.Reason.NOT_FOUND));
            String manual=definition("manual","Manual","secret-v1").replace("\"type\":\"webhook\",\"secret\":\"secret-v1\"","\"type\":\"manual\"");
            response(call("POST","/workflows",manual),201);
            response(call("POST","/workflows/manual/publish",null),200);
            assertThatThrownBy(()->tx.execute(s->factory.prepare("incompatible","manual",policy,input,TriggerType.webhook,Instant.now())))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.reason()).isEqualTo(ApiFailure.Reason.INVALID_TRIGGER));
            assertThatThrownBy(()->tx.execute(s->factory.prepare("bad-input","manual",policy,json.nullNode(),TriggerType.manual,Instant.now())))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.reason()).isEqualTo(ApiFailure.Reason.INVALID_INPUT));
            var repeated=new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for(int i=0;i<4;i++) repeated.add(client.sendAsync(request("POST","/workflows/manual/publish",null,true),HttpResponse.BodyHandlers.ofString()));
            var stableRevision=repo.findById("manual").orElseThrow().getRevision();
            for(var repeat:repeated) response(repeat.get(20,TimeUnit.SECONDS),200);
            assertThat(repo.findById("manual").orElseThrow().getRevision()).isEqualTo(stableRevision);
            // Pinned seeds all pass actual authenticated publication, including the runaway loop.
            for(JsonNode seed:json.readTree(Files.readString(Path.of("../docs/source-review/pack/data/seed_workflows.json"))).get("workflows")) {
                response(call("POST","/workflows",seed.toString()),201);
                response(call("POST","/workflows/"+seed.get("id").asString()+"/publish",null),200);
            }
            assertThat(context.getBean(QueueJobRepository.class).count()).isZero();
        } finally {proceed.countDown();}
        try(var context=start(app)) {
            assertThat(context.getBean(RunRepository.class).findById("r1").orElseThrow().getDefinitionSnapshot()).contains("First","secret-v1");
            assertThat(context.getBean(RunRepository.class).findById("r2").orElseThrow().getDefinitionSnapshot()).contains("Second","secret-v2");
            response(call("GET","/workflows/wf",null),200);
        }
    }
}
