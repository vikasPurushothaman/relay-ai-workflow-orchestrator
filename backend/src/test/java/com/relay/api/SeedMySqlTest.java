package com.relay.api;

import com.relay.RelayApplication;
import com.relay.persistence.*;
import com.relay.workflow.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class SeedMySqlTest extends com.relay.testing.MySqlIntegrationSupport {
    static volatile boolean fail=false;
    static volatile CyclicBarrier barrier;
    @TestConfiguration static class Control {
        @Bean @Primary SeedTransactions controlledSeeds(WorkflowRepository repository) { return new ControlledTransactions(repository); }
    }
    static class ControlledTransactions extends SeedTransactions {
        ControlledTransactions(WorkflowRepository repository) { super(repository); }
        @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
        public int insertMissing(List<WorkflowDefinition> definitions) {
            var gate=barrier;
            if(gate!=null) {
                try {gate.await(10,TimeUnit.SECONDS);}
                catch(Exception e) {throw new IllegalStateException("Test barrier timed out");}
                // Retry transactions must not wait for a peer that already committed.
                barrier=null;
            }
            int result=super.insertMissing(definitions);
            if(fail) throw new IllegalStateException("Test failure after batch inserts");
            return result;
        }
    }
    Map<String,List<Object>> rows(WorkflowRepository repo) {
        var result=new TreeMap<String,List<Object>>();
        for(var w:repo.findAll()) result.put(w.getId(),Arrays.asList(w.getName(),w.getDescription(),w.getStatus(),w.getDraftDefinition(),
            w.getPublishedDefinition(),w.getCreatedAt(),w.getUpdatedAt(),w.getPublishedAt(),w.getRevision()));
        return result;
    }
    @Test void startupIsRepeatableAtomicAndPreservesExistingRows() throws Exception {
        assertThat(setting("RELAY_DB_URL")).isNotBlank();
        var app=new SpringApplication(RelayApplication.class,Control.class);app.setRegisterShutdownHook(false);
        try(var context=start(app, "--RELAY_LOAD_SEEDS=false")) {
            assertThat(context.getBean(WorkflowRepository.class).count()).isZero();
            assertThat(context.getBeansOfType(com.relay.bootstrap.SeedStartup.class)).isEmpty();
        }
        Map<String,List<Object>> first;
        try(var context=start(app)) {
            var repo=context.getBean(WorkflowRepository.class);
            assertThat(repo.count()).isEqualTo(4);
            var source=context.getBean(SeedCatalog.class).definitions();
            var json=new JsonMapper();
            for(var seed:source) {
                var w=repo.findById(seed.id()).orElseThrow();
                assertThat(w.getStatus()).isEqualTo(WorkflowStatus.published);
                assertThat(json.readTree(w.getDraftDefinition())).isEqualTo(seed.json());
                assertThat(json.readTree(w.getPublishedDefinition())).isEqualTo(seed.json());
                assertThat(w.getPublishedAt()).isEqualTo(w.getCreatedAt());
            }
            first=rows(repo);
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+setting("RELAY_API_PORT")+"/workflows"))
                .header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).size()).isEqualTo(4);
            assertThat(response.body()).doesNotContain("whsec_","secret","definition");
            assertThat(context.getBean(QueueJobRepository.class).count()).isZero();
            assertThat(context.getBean(RunRepository.class).count()).isZero();
        }
        Map<String,List<Object>> edited;
        try(var context=start(app)) {
            var repo=context.getBean(WorkflowRepository.class);var loader=context.getBean(SeedLoader.class);
            assertThat(rows(repo)).isEqualTo(first);
            assertThat(loader.load()).isEqualTo(new SeedLoader.Result(0,4));
            assertThat(rows(repo)).isEqualTo(first);
            var replacement=context.getBean(SeedCatalog.class).definitions().getFirst().json();
            ((ObjectNode)replacement).put("name","My edited workflow");
            ((ObjectNode)replacement.get("trigger")).put("secret","my-private-test-secret");
            context.getBean(WorkflowService.class).update(replacement.get("id").asString(),replacement.toString());
            edited=rows(repo);
            assertThat(loader.load()).isEqualTo(new SeedLoader.Result(0,4));
            assertThat(rows(repo)).isEqualTo(edited);
            assertThat(repo.findById(replacement.get("id").asString()).orElseThrow().getStatus()).isEqualTo(WorkflowStatus.draft);
            repo.deleteById("wf_runaway");
            var kept=rows(repo);
            assertThat(loader.load()).isEqualTo(new SeedLoader.Result(1,3));
            for(var entry:kept.entrySet()) assertThat(rows(repo).get(entry.getKey())).isEqualTo(entry.getValue());
            edited=rows(repo);
        }
        try(var context=start(app)) {
            var repo=context.getBean(WorkflowRepository.class);var loader=context.getBean(SeedLoader.class);
            assertThat(rows(repo)).isEqualTo(edited);
            // All mutations below belong to this disposable test DB, which has no runs/jobs.
            repo.deleteAllInBatch();
            fail=true;
            try {assertThatThrownBy(loader::load).isInstanceOf(IllegalStateException.class);}
            finally {fail=false;}
            assertThat(repo.count()).isZero(); // Entire four-row transaction rolled back.
            barrier=new CyclicBarrier(2);
            var a=CompletableFuture.supplyAsync(loader::load);
            var b=CompletableFuture.supplyAsync(loader::load);
            var ra=a.get(30,TimeUnit.SECONDS);var rb=b.get(30,TimeUnit.SECONDS);
            assertThat(ra.created()+rb.created()).isEqualTo(4);
            assertThat(repo.count()).isEqualTo(4);
            assertThat(repo.findAll()).allSatisfy(w->assertThat(w.getStatus()).isEqualTo(WorkflowStatus.published));
            assertThat(loader.load()).isEqualTo(new SeedLoader.Result(0,4));
            var custom=context.getBean(SeedCatalog.class).definitions().getFirst().json();
            ((ObjectNode)custom).put("id","my_custom_workflow");
            context.getBean(WorkflowService.class).create(custom.toString());
            var customBefore=rows(repo).get("my_custom_workflow");
            assertThat(loader.load()).isEqualTo(new SeedLoader.Result(0,4));
            assertThat(repo.count()).isEqualTo(5);
            assertThat(rows(repo).get("my_custom_workflow")).isEqualTo(customBefore);
            repo.deleteAllInBatch();
        } finally {barrier=null;fail=false;}
        fail=true;
        try {assertThatThrownBy(()->start(app)).isInstanceOf(IllegalStateException.class);}
        finally {fail=false;}
        try(var context=start(app, "--RELAY_LOAD_SEEDS=false")) {
            assertThat(context.getBean(WorkflowRepository.class).count()).isZero();
        }
        try(var context=start(app)) {
            assertThat(context.getBean(WorkflowRepository.class).count()).isEqualTo(4);
            var client=HttpClient.newHttpClient();
            var json=new JsonMapper();
            String url="http://127.0.0.1:"+setting("RELAY_API_PORT")+"/workflows/wf_runaway";
            var detail=client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(detail.statusCode()).isEqualTo(200);
            var definition=(ObjectNode)json.readTree(detail.body()).get("definition");
            definition.put("name","Seed edited through HTTP");
            var editedResponse=client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(definition.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(editedResponse.statusCode()).as(editedResponse.body()).isEqualTo(200);
            assertThat(json.readTree(editedResponse.body()).get("status").asString()).isEqualTo("draft");
            var published=client.send(HttpRequest.newBuilder(URI.create(url+"/publish"))
                .header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
            var result=json.readTree(published.body());
            assertThat(result.get("status").asString()).isEqualTo("published");
            assertThat(result.get("published_definition")).isEqualTo(definition);
        }
        try(var context=start(app)) {
            var seed=context.getBean(WorkflowRepository.class).findById("wf_runaway").orElseThrow();
            assertThat(seed.getName()).isEqualTo("Seed edited through HTTP");
            assertThat(seed.getStatus()).isEqualTo(WorkflowStatus.published);
            var json=new JsonMapper();
            assertThat(json.readTree(seed.getPublishedDefinition())).isEqualTo(json.readTree(seed.getDraftDefinition()));
        }
    }
}
