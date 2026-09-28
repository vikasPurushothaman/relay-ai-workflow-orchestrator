package com.relay.api;

import com.relay.RelayApplication;
import com.relay.persistence.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WorkflowApiMySqlTest extends com.relay.testing.MySqlIntegrationSupport {
    final HttpClient client=HttpClient.newHttpClient();
    final JsonMapper json=new JsonMapper();
    final String base="http://127.0.0.1:"+setting("RELAY_API_PORT");
    final String token=setting("RELAY_DEMO_TOKEN");
    String definition(String id,String name) {
        return """
            {"id":"%s","name":"%s","description":"Draft","trigger":{"type":"webhook","secret":"private-sentinel"},
             "entry":"a","limits":{"max_steps":5},"nodes":[{"id":"a","type":"unknown_draft","params":{},"next":null}]}
            """.formatted(id,name);
    }
    HttpResponse<String> call(String method,String path,String body,String credential,String contentType) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path));
        if(credential!=null)b.header("Authorization","Bearer "+credential);
        if(contentType!=null)b.header("Content-Type",contentType);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> call(String method,String path,String body) throws Exception { return call(method,path,body,token,"application/json"); }
    JsonNode tree(HttpResponse<String> r,int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(r.body()).doesNotContain("private-sentinel","published-secret");
        return json.readTree(r.body());
    }
    @Test void realHttpCrudIsAtomicProtectedAndDurable() throws Exception {
        assertThat(setting("RELAY_DB_URL")).isNotBlank();
        var app=new SpringApplication(RelayApplication.class);
        app.setRegisterShutdownHook(false);
        try(var context=start(app)) {
            tree(call("GET","/workflows",null),200);
            assertThat(tree(call("GET","/workflows",null),200).isEmpty()).isTrue();
            for(String method:List.of("POST","PUT")) {
                String path=method.equals("POST")?"/workflows":"/workflows/wf";
                tree(call(method,path,definition("wf","Unauthorized"),null,"application/json"),401);
                tree(call(method,path,definition("wf","Unauthorized"),"wrong","application/json"),401);
            }
            assertThat(tree(call("GET","/workflows",null),200).isEmpty()).isTrue();
            tree(call("GET","/workflows",null,null,null),401);
            tree(call("GET","/workflows/wf",null,"wrong",null),401);
            var created=tree(call("POST","/workflows",definition("wf","First")),201);
            assertThat(created.get("status").asString()).isEqualTo("draft");
            assertThat(created.get("secret_configured").asBoolean()).isTrue();
            assertThat(created.get("definition").get("trigger").has("secret")).isFalse();
            var repo=context.getBean(WorkflowRepository.class);
            assertThat(repo.findById("wf").orElseThrow().getDraftDefinition()).contains("private-sentinel");
            tree(call("POST","/workflows",definition("wf","Overwrite")),409);
            assertThat(tree(call("GET","/workflows/wf",null),200).get("name").asString()).isEqualTo("First");
            tree(call("GET","/workflows/missing",null),404);
            tree(call("PUT","/workflows/missing",definition("missing","Missing")),404);
            assertThat(tree(call("PUT","/workflows/wf",definition("other","Mismatch")),400).get("error").get("code").asString()).isEqualTo("immutable_id");
            for(String bad:List.of("", "{", "null",definition("wf","Bad").replace("\"max_steps\":5","\"max_steps\":0"),
                    definition("wf","Bad").replace("\"params\":{}","\"params\":{\"secret-sentinel\":1,\"secret-sentinel\":2}"))) {
                tree(call("PUT","/workflows/wf",bad),400);
            }
            tree(call("POST","/workflows",definition("new","Bad media"),token,"text/plain"),415);
            tree(call("POST","/workflows"," ".repeat(1024*1024+1)),413);
            var malformed=HttpRequest.newBuilder(URI.create(base+"/workflows")).header("Authorization","Bearer "+token)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte)0xc3,0x28})).build();
            tree(client.send(malformed,HttpResponse.BodyHandlers.ofString()),400);
            // No Content-Length: bound the stream itself, not only its declared size.
            byte[] oversized=" ".repeat(1024*1024+1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var chunked=HttpRequest.newBuilder(URI.create(base+"/workflows"))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(oversized))).build();
            tree(client.send(chunked,HttpResponse.BodyHandlers.ofString()),413);
            assertThat(repo.count()).isEqualTo(1);
            assertThat(tree(call("GET","/workflows/wf",null),200).get("name").asString()).isEqualTo("First");
            String exact=definition("wf","First");
            exact += " ".repeat(1024*1024-exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            tree(call("PUT","/workflows/wf",exact),200);
            var updated=tree(call("PUT","/workflows/wf",definition("wf","Second")),200);
            assertThat(updated.get("created_at")).isEqualTo(created.get("created_at"));
            assertThat(updated.get("name").asString()).isEqualTo("Second");
            // Fixture publication is only to verify editing cannot overwrite a prior frozen definition.
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            Instant published=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            tx.executeWithoutResult(status -> {
                var w=repo.findById("wf").orElseThrow();
                ReflectionTestUtils.setField(w,"status",WorkflowStatus.published);
                ReflectionTestUtils.setField(w,"publishedDefinition",definition("wf","Earlier").replace("private-sentinel","published-secret"));
                ReflectionTestUtils.setField(w,"publishedAt",published);
            });
            tree(call("PUT","/workflows/wf",definition("wf","Edited")),200);
            var stored=repo.findById("wf").orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(WorkflowStatus.draft);
            assertThat(stored.getPublishedDefinition()).contains("published-secret");
            assertThat(stored.getPublishedAt()).isEqualTo(published);
            // Parallel inserts: exactly one winner, no overwrite.
            var creates=new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for(int i=0;i<6;i++) creates.add(client.sendAsync(HttpRequest.newBuilder(URI.create(base+"/workflows"))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(definition("race","Racer"+i))).build(),HttpResponse.BodyHandlers.ofString()));
            var statuses=creates.stream().map(f->f.join().statusCode()).toList();
            assertThat(Collections.frequency(statuses,201)).isEqualTo(1);
            assertThat(Collections.frequency(statuses,409)).isEqualTo(5);
            var edits=new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for(int i=0;i<4;i++) edits.add(client.sendAsync(HttpRequest.newBuilder(URI.create(base+"/workflows/wf"))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(definition("wf","Concurrent"+i))).build(),HttpResponse.BodyHandlers.ofString()));
            edits.forEach(f->tree(f.join(),200));
            var finalRow=repo.findById("wf").orElseThrow();
            assertThat(json.readTree(finalRow.getDraftDefinition()).get("name").asString()).isEqualTo(finalRow.getName());
            var list=tree(call("GET","/workflows",null),200);
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.get(0).get("id").asString()).isEqualTo("race");
            assertThat(list.get(0).has("definition")).isFalse();
            tree(call("DELETE","/workflows/wf",null),405);
            tree(call("POST","/workflows/wf/publish",null),400);
        }
        try(var restarted=start(app)) {
            assertThat(tree(call("GET","/workflows",null),200).size()).isEqualTo(2);
            assertThat(tree(call("GET","/workflows/wf",null),200).get("name").asString()).startsWith("Concurrent");
        }
    }
}
