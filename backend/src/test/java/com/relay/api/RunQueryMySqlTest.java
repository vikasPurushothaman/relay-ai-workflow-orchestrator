package com.relay.api;

import com.relay.RelayApplication;
import com.relay.engine.*;
import com.relay.persistence.TriggerType;
import com.relay.security.ManagementAuthentication;
import com.relay.testing.MySqlIntegrationSupport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP and MySQL checks for the run list and run detail read routes (task 6.1). */
class RunQueryMySqlTest extends MySqlIntegrationSupport {
    static final String SECRET="run-query-secret-sentinel";
    static final String LOOP="[{\"id\":\"a\",\"type\":\"delay\",\"params\":{\"seconds\":0},\"next\":\"b\"},"
        +"{\"id\":\"b\",\"type\":\"condition\",\"params\":{\"left\":\"1\",\"op\":\"equals\",\"right\":\"2\"},\"on_true\":null,\"on_false\":\"a\"}]";
    static final String APPROVAL="[{\"id\":\"a\",\"type\":\"approval\",\"params\":{\"message\":\"Review\"},\"next\":null}]";
    final HttpClient client=HttpClient.newHttpClient();

    ConfigurableApplicationContext api() {
        var app=new SpringApplication(RelayApplication.class);app.setRegisterShutdownHook(false);return start(app,"--RELAY_HTTP_ALLOWED_ORIGINS=http://127.0.0.1:9");
    }
    String workflow(ConfigurableApplicationContext c,String nodes,int cap) {
        String id="wf_"+UUID.randomUUID().toString().replace("-","");
        c.getBean(WorkflowService.class).create("{\"id\":\""+id+"\",\"name\":\"Run query test\",\"trigger\":{\"type\":\"webhook\",\"secret\":\""+SECRET+"\"},\"entry\":\"a\",\"limits\":{\"max_steps\":"+cap+"},\"nodes\":"+nodes+"}");
        c.getBean(PublicationService.class).publish(id);return id;
    }
    String accept(ConfigurableApplicationContext c,String wf){return c.getBean(AcceptanceService.class).accept(wf,EngineJson.read("{\"secret_like\":\"input-sentinel\"}"),TriggerType.manual,null);}
    static <T>T human(Supplier<T> work) {
        var ctx=SecurityContextHolder.createEmptyContext();ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ManagementAuthentication.PRINCIPAL,null,List.of(new SimpleGrantedAuthority(ManagementAuthentication.AUTHORITY))));
        SecurityContextHolder.setContext(ctx);try{return work.get();}finally{SecurityContextHolder.clearContext();}
    }
    void drive(ConfigurableApplicationContext c,String id)throws Exception {
        var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
        for(int i=0;i<200 && Set.of("queued","running").contains(sql.queryForObject("SELECT status FROM runs WHERE run_id=?",String.class,id));i++) {
            var claim=store.claim(id,"test-worker");
            if(claim==null){Thread.sleep(10);continue;}
            assertThat(store.prepare(claim)).as("delay/condition/approval never dispatch").isNull();
        }
    }
    HttpResponse<String> get(String path,String... headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+setting("RELAY_API_PORT")+path)).GET();
        if(headers.length>0)b.headers(headers);return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path)throws Exception {
        var r=get(path,"Authorization","Bearer "+setting("RELAY_DEMO_TOKEN"));
        assertThat(r.statusCode()).as(path+" -> "+r.body()).isEqualTo(200);
        assertThat(r.body()).doesNotContain(SECRET,"input-sentinel","definition_snapshot","execution_policy");
        return EngineJson.read(r.body());
    }
    void fails(String path,int status,String code)throws Exception {
        var r=get(path,"Authorization","Bearer "+setting("RELAY_DEMO_TOKEN"));
        assertThat(r.statusCode()).as(path).isEqualTo(status);
        assertThat(EngineJson.read(r.body()).get("error").get("code").asString()).isEqualTo(code);
    }
    List<String> ids(JsonNode page){var out=new ArrayList<String>();for(var r:page.get("runs"))out.add(r.get("run_id").asString());return out;}
    List<String> all(String query,int limit)throws Exception {
        var out=new ArrayList<String>();String cursor=null;
        for(int i=0;i<100;i++) {
            var page=ok("/runs?limit="+limit+query+(cursor==null?"":"&cursor="+cursor));
            assertThat(page.get("runs").size()).isLessThanOrEqualTo(limit);out.addAll(ids(page));
            if(page.get("next_cursor").isNull())return out;
            cursor=page.get("next_cursor").asString();
        }
        throw new AssertionError("pagination did not terminate");
    }
    static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}

    @Test void routesRequireManagementAuthentication()throws Exception {
        try(var c=api()) {
            for(String path:new String[]{"/runs","/runs/anything","/runs?status=failed"}) {
                assertThat(get(path).statusCode()).isEqualTo(401);
                assertThat(get(path,"Authorization","Bearer wrong-token").statusCode()).isEqualTo(401);
                assertThat(get(path,"Authorization","Basic abc").statusCode()).isEqualTo(401);
            }
        }
    }

    @Test void listFiltersAndPagesStablyServerSide()throws Exception {
        try(var c=api()) {
            var sql=c.getBean(JdbcTemplate.class);
            String wfA=workflow(c,APPROVAL,1),wfB=workflow(c,APPROVAL,1);
            var aRuns=new ArrayList<String>();for(int i=0;i<3;i++)aRuns.add(accept(c,wfA));
            var bRuns=new ArrayList<String>();for(int i=0;i<2;i++)bRuns.add(accept(c,wfB));
            // Force a timestamp tie so ordering must fall back to run_id.
            sql.update("UPDATE runs SET created_at=(SELECT t FROM (SELECT created_at t FROM runs WHERE run_id=?) x) WHERE run_id=?",aRuns.get(0),aRuns.get(1));
            human(()->c.getBean(HumanControlService.class).cancel(bRuns.get(0)));
            var expected=sql.queryForList("SELECT run_id FROM runs WHERE workflow_id IN (?,?) ORDER BY created_at DESC,run_id DESC",String.class,wfA,wfB);
            assertThat(expected).hasSize(5);

            var first=ok("/runs");
            assertThat(first.get("next_cursor").isNull()).isTrue();
            assertThat(ids(first)).isEqualTo(sql.queryForList("SELECT run_id FROM runs ORDER BY created_at DESC,run_id DESC LIMIT 25",String.class));
            var row=first.get("runs").get(0);
            for(String f:new String[]{"run_id","workflow_id","status","trigger_type","current_node_id","steps_executed","max_steps","created_at","started_at","finished_at"})assertThat(row.has(f)).as(f).isTrue();
            assertThat(row.size()).isEqualTo(10);assertThat(row.get("max_steps").asLong()).isEqualTo(1);

            // The projection remains truthful for a legacy/corrupt snapshot without a cap.
            sql.update("UPDATE runs SET definition_snapshot=JSON_REMOVE(definition_snapshot,'$.limits.max_steps') WHERE run_id=?",row.get("run_id").asString());
            var withoutCap=ok("/runs?limit=100").get("runs");
            for(var candidate:withoutCap)if(candidate.get("run_id").asString().equals(row.get("run_id").asString()))assertThat(candidate.get("max_steps").isNull()).isTrue();

            String both="&workflow_id="+wfA;
            assertThat(all(both,1)).isEqualTo(expected.stream().filter(aRuns::contains).toList());
            assertThat(all("&workflow_id="+wfB+"&status=cancelled",1)).containsExactly(bRuns.get(0));
            assertThat(all("&workflow_id="+wfB+"&status=queued",10)).containsExactly(bRuns.get(1));
            var none=ok("/runs?workflow_id=wf_missing");
            assertThat(none.get("runs").size()).isZero();assertThat(none.get("next_cursor").isNull()).isTrue();
            assertThat(ok("/runs?status=")).isEqualTo(ok("/runs"));

            // Exact page boundary: limit equal to the remaining count ends with a null cursor.
            var exact=ok("/runs?limit=3"+both);assertThat(exact.get("runs").size()).isEqualTo(3);assertThat(exact.get("next_cursor").isNull()).isTrue();

            // A run accepted between pages does not duplicate or shift the continuation.
            var page1=ok("/runs?limit=2&workflow_id="+wfA);String cursor=page1.get("next_cursor").asString();
            String newer=accept(c,wfA);
            var page2=ok("/runs?limit=2&workflow_id="+wfA+"&cursor="+cursor);
            var seen=new ArrayList<>(ids(page1));seen.addAll(ids(page2));
            assertThat(seen).isEqualTo(expected.stream().filter(aRuns::contains).toList()).doesNotContain(newer);
            assertThat(ids(ok("/runs?limit=1&workflow_id="+wfA))).containsExactly(newer);

            for(String bad:new String[]{"?limit=0","?limit=101","?limit=x","?status=pending","?status=queued&status=failed","?cursor=%21%21","?cursor=abc","?page=2",
                    "?workflow_id="+"x".repeat(129),"?cursor="+Base64.getUrlEncoder().withoutPadding().encodeToString("300000000000000000:r".getBytes(StandardCharsets.UTF_8))})
                fails("/runs"+bad,400,"invalid_input");
        }
    }

    @Test void detailReturnsCompleteOrderedStepsAcrossPages()throws Exception {
        try(var c=api()) {
            var sql=c.getBean(JdbcTemplate.class);
            String queued=accept(c,workflow(c,LOOP,5));
            var q=ok("/runs/"+queued);
            assertThat(q.get("status").asString()).isEqualTo("queued");assertThat(q.get("steps").size()).isZero();
            assertThat(q.get("steps_next_after").isNull()).isTrue();assertThat(q.get("max_steps").asLong()).isEqualTo(5);
            assertThat(q.get("error").isNull()).isTrue();assertThat(q.get("current_node_id").asString()).isEqualTo("a");

            drive(c,queued);
            var done=ok("/runs/"+queued);
            assertThat(done.get("status").asString()).isEqualTo("failed");
            assertThat(done.get("error").get("code").asString()).isEqualTo("max_steps");
            assertThat(done.get("steps_executed").asLong()).isEqualTo(5);assertThat(done.get("finished_at").isNull()).isFalse();
            var steps=done.get("steps");assertThat(steps.size()).isEqualTo(5);
            var nodes=new ArrayList<String>();
            for(int i=0;i<5;i++) {
                var s=steps.get(i);nodes.add(s.get("node_id").asString());
                assertThat(s.get("sequence").asLong()).isEqualTo(i+1);assertThat(s.get("status").asString()).isEqualTo("succeeded");
                assertThat(s.get("duration_ms").isNull()).isFalse();assertThat(s.get("wait_reason").isNull()).isTrue();
                assertThat(s.get("attempts").size()).isEqualTo(1);assertThat(s.get("resolved_input").isObject()).isTrue(); // payload fields added by task6.2
            }
            assertThat(nodes).containsExactly("a","b","a","b","a");
            assertThat(steps.get(1).get("output").get("result").asBoolean()).isFalse();assertThat(steps.get(0).get("output").size()).isZero();
            assertThat(steps.get(0).get("node_type").asString()).isEqualTo("delay");
            // One prepared handler invocation; the durable wake-up does not add an attempt.
            assertThat(steps.get(0).get("attempt_count").asLong()).isEqualTo(1);assertThat(steps.get(0).get("resume_at").isNull()).isFalse();
            assertThat(done.get("error").get("node_id").asString()).isEqualTo("b");assertThat(done.get("current_node_id").asString()).isEqualTo("b");
            assertThat(done.get("cancel_requested_at").isNull()).isTrue();assertThat(done.get("cancellation_reason").isNull()).isTrue();
            assertThat(steps.get(1).get("selected_next_node_id").asString()).isEqualTo("a");

            var paged=new ArrayList<Long>();long after=0;
            for(int i=0;i<10;i++) {
                var p=ok("/runs/"+queued+"?steps_limit=2&steps_after="+after);
                assertThat(p.get("status").asString()).isEqualTo("failed");
                for(var s:p.get("steps"))paged.add(s.get("sequence").asLong());
                if(p.get("steps_next_after").isNull())break;
                after=p.get("steps_next_after").asLong();
            }
            assertThat(paged).containsExactly(1L,2L,3L,4L,5L);
            assertThat(ok("/runs/"+queued+"?steps_after=5").get("steps").size()).isZero();
            assertThat(ok("/runs/"+queued+"?steps_limit=5").get("steps_next_after").isNull()).isTrue();

            String waiting=accept(c,workflow(c,APPROVAL,1));drive(c,waiting);
            var w=ok("/runs/"+waiting);
            assertThat(w.get("status").asString()).isEqualTo("waiting_approval");
            assertThat(w.get("steps").get(0).get("status").asString()).isEqualTo("waiting");
            assertThat(w.get("steps").get(0).get("wait_reason").asString()).isEqualTo("approval");
            assertThat(w.get("steps").get(0).get("finished_at").isNull()).isTrue();
            human(()->c.getBean(HumanControlService.class).cancel(waiting));
            var x=ok("/runs/"+waiting);
            assertThat(x.get("status").asString()).isEqualTo("cancelled");
            assertThat(x.get("cancel_requested_by").asString()).isEqualTo("demo-operator");
            assertThat(x.get("cancellation_reason").asString()).isEqualTo("operator_cancelled");
            assertThat(x.get("cancel_requested_at").isNull()).isFalse();
            assertThat(x.get("steps").get(0).get("status").asString()).isEqualTo("cancelled");

            fails("/runs/run_missing",404,"not_found");
            fails("/runs/"+enc("x".repeat(129)),404,"not_found");
            for(String bad:new String[]{"?steps_limit=0","?steps_limit=501","?steps_after=-1","?steps_after=x","?limit=2"})
                fails("/runs/"+queued+bad,400,"invalid_input");
            fails("/runs/run_missing?steps_limit=0",400,"invalid_input");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM steps WHERE run_id=?",Long.class,queued)).isEqualTo(5);
        }
    }

    @Test void traceIncludesRedactedPayloadAttemptsUsageAndApprovalEvidence()throws Exception {
        try(var c=api()) {
            var store=c.getBean(EngineStore.class);
            // http_request: sensitive header and query parameter; a retryable failure leaves the step waiting for retry.
            String http=workflow(c,"[{\"id\":\"a\",\"type\":\"http_request\",\"params\":{\"method\":\"POST\",\"url\":\"http://127.0.0.1:9/x?api_key=QUERYSENTINEL&ok=1\","
                +"\"headers\":{\"X-Api-Key\":\"HEADERSENTINEL\",\"X-Trace\":\"visible\"},\"body\":{\"note\":\"{{trigger.body.note}}\"}},\"next\":null}]",3);
            String hr=c.getBean(AcceptanceService.class).accept(http,EngineJson.read("{\"note\":\"echo "+SECRET+"\"}"),TriggerType.webhook,SECRET);
            var p=store.prepare(store.claim(hr,"test-worker"));assertThat(p).as(c.getBean(JdbcTemplate.class).queryForObject("SELECT CONCAT(COALESCE(error,''),' ',input) FROM runs WHERE run_id=?",String.class,hr)).isNotNull();
            store.finish(p,new HttpTransport.Outcome(null,"http_503",true,null,null,null));
            var h=ok("/runs/"+hr);
            assertThat(h.toString()).doesNotContain("QUERYSENTINEL","HEADERSENTINEL");
            assertThat(h.get("workflow_name").asString()).isEqualTo("Run query test");assertThat(h.get("entry").asString()).isEqualTo("a");
            assertThat(h.get("input").get("note").asString()).isEqualTo("echo [REDACTED]");
            var hs=h.get("steps").get(0);
            assertThat(hs.get("status").asString()).isEqualTo("waiting");assertThat(hs.get("wait_reason").asString()).isEqualTo("retry");
            assertThat(hs.get("retry_due_at").isNull()).isFalse();
            assertThat(hs.get("resolved_input").get("headers").get("X-Api-Key").asString()).isEqualTo("[REDACTED]");
            assertThat(hs.get("resolved_input").get("headers").get("X-Trace").asString()).isEqualTo("visible");
            assertThat(hs.get("resolved_input").get("url").asString()).isEqualTo("http://127.0.0.1:9/x?api_key=[REDACTED]&ok=1");
            assertThat(hs.get("resolved_input").get("body").get("note").asString()).isEqualTo("echo [REDACTED]");
            assertThat(hs.get("idempotency_key").asString()).isEqualTo(hr+":1");
            assertThat(hs.get("error").get("code").asString()).isEqualTo("http_503");assertThat(hs.get("error").get("retry_number").asLong()).isEqualTo(1);
            var ha=hs.get("attempts");assertThat(ha.size()).isEqualTo(1);
            assertThat(ha.get(0).get("attempt_no").asLong()).isEqualTo(1);assertThat(ha.get(0).get("status").asString()).isEqualTo("failed");
            assertThat(ha.get(0).get("cause").asString()).isEqualTo("initial");assertThat(ha.get(0).get("error").get("code").asString()).isEqualTo("http_503");
            assertThat(ha.get(0).get("tokens_prompt").isNull()).isTrue();assertThat(ha.get(0).get("duration_ms").isNull()).isFalse();
            assertThat(hs.has("dispatch_request")).isFalse();assertThat(ha.get(0).has("request")).isFalse();

            // ai: canned provider outcome with usage; a credential-like output key is redacted.
            String ai=workflow(c,"[{\"id\":\"a\",\"type\":\"ai\",\"params\":{\"prompt\":\"Classify\",\"output_schema\":{\"type\":\"object\",\"required\":[\"ok\"]}},\"next\":\"b\"},"
                +"{\"id\":\"b\",\"type\":\"approval\",\"params\":{\"message\":\"Approve {{nodes.a.output.ok}}\"},\"next\":null}]",5);
            String ar=accept(c,ai);
            var ap=store.prepare(store.claim(ar,"test-worker"));
            store.finish(ap,new HttpTransport.Outcome(EngineJson.JSON.getNodeFactory().stringNode("{\"ok\":true,\"session_token\":\"OUTSENTINEL\"}"),null,false,null,7L,3L));
            drive(c,ar);
            String aid=c.getBean(JdbcTemplate.class).queryForObject("SELECT id FROM approvals WHERE run_id=?",String.class,ar);
            human(()->c.getBean(HumanControlService.class).decide(aid,true));
            var a=ok("/runs/"+ar);
            assertThat(a.toString()).doesNotContain("OUTSENTINEL");
            assertThat(a.get("status").asString()).isEqualTo("succeeded");
            assertThat(a.get("ai_tokens_used").asLong()).isEqualTo(10);assertThat(a.get("ai_usage_complete").asBoolean()).isTrue();
            var s1=a.get("steps").get(0);
            assertThat(s1.get("output").get("ok").asBoolean()).isTrue();assertThat(s1.get("output").get("session_token").asString()).isEqualTo("[REDACTED]");
            assertThat(s1.get("tokens_prompt").asLong()).isEqualTo(7);assertThat(s1.get("tokens_completion").asLong()).isEqualTo(3);assertThat(s1.get("ai_usage_complete").asBoolean()).isTrue();
            assertThat(s1.get("resolved_input").get("prompt").asString()).isEqualTo("Classify");assertThat(s1.get("ai_repair_count").asLong()).isZero();
            var at=s1.get("attempts").get(0);
            assertThat(at.get("provider").asString()).isEqualTo("mock-http");assertThat(at.get("model").asString()).isEqualTo("alpha-small");
            assertThat(at.get("tokens_prompt").asLong()).isEqualTo(7);assertThat(at.get("status").asString()).isEqualTo("succeeded");
            assertThat(s1.get("approval").isNull()).isTrue();assertThat(s1.get("retry_due_at").isNull()).isTrue();
            var s2=a.get("steps").get(1);
            assertThat(s2.get("approval").get("id").asString()).isEqualTo(aid);assertThat(s2.get("approval").get("status").asString()).isEqualTo("approved");
            assertThat(s2.get("approval").get("message").asString()).isEqualTo("Approve true");
            assertThat(s2.get("approval").get("decided_by").asString()).isEqualTo("demo-operator");assertThat(s2.get("approval").get("decided_at").isNull()).isFalse();
            assertThat(s2.get("output").get("decision").asString()).isEqualTo("approved");

            // A queued run has no attempts yet; the redacted input still appears.
            var fresh=ok("/runs/"+accept(c,ai));
            assertThat(fresh.get("input").get("secret_like").asString()).isEqualTo("[REDACTED]");assertThat(fresh.get("ai_tokens_used").asLong()).isZero();
        }
    }
}
