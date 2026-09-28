package com.relay.engine;

import com.relay.RelayApplication;
import com.relay.api.*;
import com.relay.persistence.TriggerType;
import com.relay.testing.MySqlIntegrationSupport;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class EngineMySqlTest extends MySqlIntegrationSupport {
    final HttpClient client=HttpClient.newHttpClient();
    ConfigurableApplicationContext api(String... extra) {
        var app=new SpringApplication(RelayApplication.class);app.setRegisterShutdownHook(false);
        var args=new ArrayList<>(List.of("--RELAY_RETRY_BASE_MS=10","--RELAY_RETRY_MAX_MS=20"));args.addAll(List.of(extra));
        return start(app,args.toArray(String[]::new));
    }
    String workflow(ConfigurableApplicationContext context,String nodes,int cap) {
        String id="wf_"+UUID.randomUUID().toString().replace("-","");
        context.getBean(WorkflowService.class).create("{\"id\":\""+id+"\",\"name\":\"Engine test\",\"trigger\":{\"type\":\"webhook\",\"secret\":\"test-secret\"},\"entry\":\"a\",\"limits\":{\"max_steps\":"+cap+"},\"nodes\":"+nodes+"}");
        context.getBean(PublicationService.class).publish(id);return id;
    }
    String accept(ConfigurableApplicationContext c,String workflow) {return c.getBean(AcceptanceService.class).accept(workflow,EngineJson.read("{}"),TriggerType.manual,null);}
    tools.jackson.databind.JsonNode contextDefinition(ConfigurableApplicationContext c,String id) {
        return EngineJson.read(c.getBean(JdbcTemplate.class).queryForObject("SELECT draft_definition FROM workflows WHERE id=?",String.class,id));
    }
    HttpResponse<String> call(String path,String body,String... headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+setting("RELAY_API_PORT")+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(headers.length>0)b.headers(headers);return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    String status(JdbcTemplate sql,String id){return sql.queryForObject("SELECT status FROM runs WHERE run_id=?",String.class,id);}
    void expire(JdbcTemplate sql,String id){sql.update("UPDATE queue_jobs SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE run_id=?",id);}
    void due(JdbcTemplate sql,String id){sql.update("UPDATE queue_jobs SET available_at=UTC_TIMESTAMP(6) WHERE run_id=?",id);}
    void drive(ConfigurableApplicationContext c,String id,DestinationPolicy destinations)throws Exception {
        var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
        for(int i=0;i<100 && Set.of("queued","running").contains(status(sql,id));i++) {
            var claim=store.claim(id,"test-worker");
            if(claim!=null){var p=store.prepare(claim);if(p!=null)store.finish(p,c.getBean(HttpTransport.class).send(p,destinations));}
            else Thread.sleep(10);
        }
    }
    @Test void triggersAuthorizeValidateAndCommitAtomically()throws Exception {
        try(var c=api()) {
            var sql=c.getBean(JdbcTemplate.class);String wf=workflow(c,"[{\"id\":\"a\",\"type\":\"delay\",\"params\":{\"seconds\":0},\"next\":null}]",1);
            long count=sql.queryForObject("SELECT COUNT(*) FROM runs",Long.class);
            assertThat(call("/workflows/"+wf+"/trigger","{\"input\":{}}").statusCode()).isEqualTo(401);
            for(String body:new String[]{"{}","{\"input\":null}","{\"input\":{},\"extra\":1}","{\"input\":{},\"input\":{}}","{} {}","{"})
                assertThat(call("/workflows/"+wf+"/trigger",body,"Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).statusCode()).isEqualTo(400);
            assertThat(call("/hooks/"+wf,"{}").statusCode()).isEqualTo(401);
            assertThat(call("/hooks/"+wf,"{}","X-Relay-Secret","wrong").statusCode()).isEqualTo(401);
            assertThat(call("/hooks/"+wf,"{}","X-Relay-Secret","test-secret","X-Relay-Secret","test-secret").statusCode()).isEqualTo(401);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM runs",Long.class)).isEqualTo(count);
            var first=call("/hooks/"+wf,"null","X-Relay-Secret","test-secret");assertThat(first.statusCode()).isEqualTo(202);
            String id=EngineJson.read(first.body()).get("run_id").asString();
            assertThat(status(sql,id)).isEqualTo("queued");assertThat(sql.queryForObject("SELECT input FROM runs WHERE run_id=?",String.class,id)).isEqualTo("null");
            assertThat(sql.queryForObject("SELECT next_attempt_cause FROM queue_jobs WHERE run_id=?",String.class,id)).isEqualTo("initial");
            var second=call("/workflows/"+wf+"/trigger","{\"input\":{}}","Authorization","Bearer "+setting("RELAY_DEMO_TOKEN"));
            assertThat(second.statusCode()).isEqualTo(202);assertThat(second.body()).isNotEqualTo(first.body());
            long before=sql.queryForObject("SELECT COUNT(*) FROM runs",Long.class);
            // Only the disposable container administrator injects the failure; application privileges stay unchanged.
            try(var admin=java.sql.DriverManager.getConnection(setting("RELAY_DB_URL"),"root",setting("RELAY_DB_PASSWORD"));var statement=admin.createStatement()) {
                statement.execute("CREATE TRIGGER reject_queue BEFORE INSERT ON queue_jobs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'");
                try{assertThatThrownBy(()->accept(c,wf)).isInstanceOf(RuntimeException.class);}
                finally{statement.execute("DROP TRIGGER reject_queue");}
            }
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM runs",Long.class)).isEqualTo(before);
            assertThat(sql.queryForList("EXPLAIN SELECT run_id FROM queue_jobs WHERE status='ready' AND available_at<=UTC_TIMESTAMP(6) ORDER BY available_at,run_id LIMIT 32").getFirst().get("key")).isEqualTo("ix_queue_due");
            assertThat(sql.queryForList("EXPLAIN SELECT run_id FROM queue_jobs WHERE status='leased' AND lease_until<=UTC_TIMESTAMP(6) ORDER BY lease_until,run_id LIMIT 32").getFirst().get("key")).isEqualTo("ix_queue_expired");
            assertThat(call("/workflows/missing/trigger","{\"input\":{}}","Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).statusCode()).isEqualTo(404);
            assertThat(call("/hooks/missing","{}","X-Relay-Secret","test-secret").statusCode()).isEqualTo(404);
            assertThat(call("/workflows/"+wf+"/trigger","{\"input\":{\"x\":\""+"x".repeat(EngineJson.MAX_BYTES)+"\"}}","Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).statusCode()).isEqualTo(413);
            var media=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+setting("RELAY_API_PORT")+"/workflows/"+wf+"/trigger"))
                .header("Authorization","Bearer "+setting("RELAY_DEMO_TOKEN")).header("Content-Type","text/plain").POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertThat(client.send(media.build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
            var definition=contextDefinition(c,wf);((tools.jackson.databind.node.ObjectNode)definition.get("trigger")).put("secret","rotated-secret");
            c.getBean(WorkflowService.class).update(wf,definition.toString());
            assertThat(call("/hooks/"+wf,"{}","X-Relay-Secret","test-secret").statusCode()).isEqualTo(409);
            c.getBean(PublicationService.class).publish(wf);
            assertThat(call("/hooks/"+wf,"{}","X-Relay-Secret","test-secret").statusCode()).isEqualTo(401);
            assertThat(call("/hooks/"+wf,"{}","X-Relay-Secret","rotated-secret").statusCode()).isEqualTo(202);
            ((tools.jackson.databind.node.ObjectNode)definition).put("id","manual-only-test");
            ((tools.jackson.databind.node.ObjectNode)definition).set("trigger",EngineJson.read("{\"type\":\"manual\"}"));
            c.getBean(WorkflowService.class).create(definition.toString());c.getBean(PublicationService.class).publish("manual-only-test");
            assertThat(call("/hooks/manual-only-test","{}","X-Relay-Secret","test-secret").statusCode()).isEqualTo(409);

        }
    }
    @Test void branchesDelayRestartAndCapCountLogicalVisits()throws Exception {
        String id;
        try(var c=api()) {
            String wf=workflow(c,"[{\"id\":\"a\",\"type\":\"condition\",\"params\":{\"left\":\"100\",\"op\":\"greater_than\",\"right\":\"100\"},\"on_true\":null,\"on_false\":\"b\"},{\"id\":\"b\",\"type\":\"delay\",\"params\":{\"seconds\":0.1},\"next\":\"a\"}]",4);
            id=accept(c,wf);var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
            assertThat(store.prepare(store.claim(id,"old"))).isNull();
            assertThat(store.prepare(store.claim(id,"old"))).isNull();
            assertThat(sql.queryForObject("SELECT steps_executed FROM runs WHERE run_id=?",Long.class,id)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM step_attempts WHERE run_id=?",Long.class,id)).isEqualTo(2);
        }
        Thread.sleep(130);
        try(var c=api()) {
            drive(c,id,new DestinationPolicy("http://localhost:9210"));var sql=c.getBean(JdbcTemplate.class);
            assertThat(status(sql,id)).isEqualTo("failed");
            assertThat(sql.queryForObject("SELECT error FROM runs WHERE run_id=?",String.class,id)).contains("max_steps");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM steps WHERE run_id=?",Long.class,id)).isEqualTo(4);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM step_attempts WHERE run_id=?",Long.class,id)).isEqualTo(4);
            String finalRun=accept(c,workflow(c,"[{\"id\":\"a\",\"type\":\"delay\",\"params\":{\"seconds\":0},\"next\":null}]",1));
            drive(c,finalRun,new DestinationPolicy("http://localhost:9210"));assertThat(status(sql,finalRun)).isEqualTo("succeeded");
        }
    }
    @Test void ownershipRecoveryRetryBudgetsAndDuplicateOutcomesAreFenced()throws Exception {
        try(var c=api()) {
            String wf=workflow(c,"[{\"id\":\"a\",\"type\":\"notify\",\"params\":{\"channel\":\"email\",\"to\":\"test@example.com\",\"message\":\"Hi\"},\"next\":null}]",1);
            String id=accept(c,wf);var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
            var claim=store.claim(id,"old");assertThat(store.claim(id,"other")).isNull();
            var first=store.prepare(claim);assertThat(store.renew(first.claim())).isTrue();
            expire(sql,id);assertThat(store.renew(first.claim())).isFalse();
            var newClaim=store.claim(id,"replacement");assertThat(newClaim.generation()).isGreaterThan(claim.generation());
            assertThatThrownBy(()->store.finish(first,new HttpTransport.Outcome(EngineJson.read("{}"),null,false,null))).isInstanceOf(EngineStore.LostLease.class);
            assertThat(store.prepare(newClaim)).isNull();
            assertThat(sql.queryForObject("SELECT status FROM step_attempts WHERE run_id=? AND attempt_no=1",String.class,id)).isEqualTo("uncertain");
            due(sql,id);var retry=store.prepare(store.claim(id,"replacement"));
            assertThat(retry.key()).isEqualTo(first.key());assertThat(retry.request()).isEqualTo(first.request());
            store.finish(retry,HttpTransport.Outcome.failed("http_503",true));due(sql,id);
            var last=store.prepare(store.claim(id,"replacement"));assertThat(last.attempt()).isEqualTo(3);
            store.finish(last,HttpTransport.Outcome.failed("http_503",true));
            assertThat(status(sql,id)).isEqualTo("failed");assertThat(sql.queryForObject("SELECT steps_executed FROM runs WHERE run_id=?",Long.class,id)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM step_attempts WHERE run_id=?",Long.class,id)).isEqualTo(3);
            assertThatThrownBy(()->store.finish(last,HttpTransport.Outcome.failed("http_503",true))).isInstanceOf(EngineStore.LostLease.class);
            String ok=accept(c,wf);var p=store.prepare(store.claim(ok,"worker"));
            store.finish(p,new HttpTransport.Outcome(EngineJson.read("{\"delivered\":true,\"notification_id\":\"x\"}"),null,false,null));
            assertThat(status(sql,ok)).isEqualTo("succeeded");
            assertThatThrownBy(()->store.finish(p,new HttpTransport.Outcome(EngineJson.read("{}"),null,false,null))).isInstanceOf(EngineStore.LostLease.class);
            assertThat(store.claim(ok,"again")).isNull();
        }
    }
    @Test void missingTemplateAndSensitiveActionFailBeforeAnyDispatch() {
        try(var c=api()) {
            var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
            for(String params:List.of("\"type\":\"notify\",\"params\":{\"channel\":\"email\",\"to\":\"{{trigger.body.absent}}\",\"message\":\"x\"}","\"type\":\"order_action\",\"params\":{\"action\":\"refund\",\"order_id\":\"ord_1001\"}")) {
                String id=accept(c,workflow(c,"[{\"id\":\"a\","+params+",\"next\":null}]",1));
                assertThat(store.prepare(store.claim(id,"worker"))).isNull();assertThat(status(sql,id)).isEqualTo("failed");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM step_attempts WHERE run_id=?",Long.class,id)).isZero();
            }
        }
    }
    @Test void latestEarlierOutputAndLoopKeysSurviveDistinctVisits() {
        try(var c=api()) {
            String wf=workflow(c,"""
                [{"id":"a","type":"notify","params":{"channel":"chat","to":"ops","message":"loop"},"next":"b"},
                 {"id":"b","type":"condition","params":{"left":"{{nodes.a.output.notification_id}}","op":"equals","right":"n2"},"on_true":null,"on_false":"a"}]
                """,4);
            String id=accept(c,wf);var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
            var first=store.prepare(store.claim(id,"worker"));
            store.finish(first,new HttpTransport.Outcome(EngineJson.read("{\"delivered\":true,\"notification_id\":\"n1\"}"),null,false,null));
            assertThat(store.prepare(store.claim(id,"worker"))).isNull();
            var second=store.prepare(store.claim(id,"worker"));
            assertThat(second.key()).isEqualTo(id+":3").isNotEqualTo(first.key());
            store.finish(second,new HttpTransport.Outcome(EngineJson.read("{\"delivered\":true,\"notification_id\":\"n2\"}"),null,false,null));
            assertThat(store.prepare(store.claim(id,"worker"))).isNull();
            assertThat(status(sql,id)).isEqualTo("succeeded");
            assertThat(EngineJson.read(sql.queryForObject("SELECT resolved_input FROM steps WHERE run_id=? AND sequence=4",String.class,id)).get("left").asString()).isEqualTo("n2");
        }
    }
    @Test void recoveryBeforePreparationDoesNotConsumeAttemptAndRetryAfterIsDurable() {
        try(var c=api()) {
            String wf=workflow(c,"[{\"id\":\"a\",\"type\":\"notify\",\"params\":{\"channel\":\"email\",\"to\":\"x\",\"message\":\"x\"},\"next\":null}]",1);
            String id=accept(c,wf);var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
            var old=store.claim(id,"crashed-before-prepare");expire(sql,id);
            var p=store.prepare(store.claim(id,"replacement"));assertThat(p.attempt()).isEqualTo(1);
            assertThatThrownBy(()->store.prepare(old)).isInstanceOf(EngineStore.LostLease.class);
            store.finish(p,new HttpTransport.Outcome(null,"http_429",true,"3"));
            assertThat(store.claim(id,"too-early")).isNull();
            assertThat(sql.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),available_at) FROM queue_jobs WHERE run_id=?",Long.class,id)).isGreaterThan(2_000_000);
            due(sql,id);var before=store.claim(id,"crashed-before-retry");expire(sql,id);
            var retry=store.prepare(store.claim(id,"replacement"));assertThat(retry.attempt()).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT retry_count FROM queue_jobs WHERE run_id=?",Long.class,id)).isEqualTo(1);
            assertThat(retry.request()).isEqualTo(p.request());
            store.finish(retry,new HttpTransport.Outcome(EngineJson.read("{}"),null,false,null));
            assertThat(status(sql,id)).isEqualTo("succeeded");
        }
    }
    @Test void invalidPersistedPolicyCannotDispatchOrStarveOtherRuns()throws Exception {
        try(var c=api()) {
            String wf=workflow(c,"[{\"id\":\"a\",\"type\":\"delay\",\"params\":{\"seconds\":0},\"next\":null}]",1);
            String bad=accept(c,wf),good=accept(c,wf);var sql=c.getBean(JdbcTemplate.class);
            sql.update("UPDATE runs SET execution_policy='{\"policy_version\":99}' WHERE run_id=?",bad);
            var worker=new WorkerLifecycle(c.getBean(EngineStore.class),c.getBean(HttpTransport.class),new org.springframework.mock.env.MockEnvironment().withProperty("RELAY_JOB_POLL_MS","10"),c.getBean(com.relay.ai.AiProvider.class));
            try {
                worker.start();
                for(int i=0;i<100 && !status(sql,good).equals("succeeded");i++)Thread.sleep(20);
                assertThat(status(sql,good)).isEqualTo("succeeded");assertThat(status(sql,bad)).isEqualTo("queued");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM steps WHERE run_id=?",Long.class,bad)).isZero();
            }finally{worker.stop();}
        }
    }

    @Test void failedOutcomeTransactionRollsBackAttemptStepAndJobTogether()throws Exception {
        try(var c=api()) {
            String wf=workflow(c,"[{\"id\":\"a\",\"type\":\"notify\",\"params\":{\"channel\":\"email\",\"to\":\"x\",\"message\":\"x\"},\"next\":null}]",1);
            String id=accept(c,wf);var store=c.getBean(EngineStore.class);var sql=c.getBean(JdbcTemplate.class);
            var prepared=store.prepare(store.claim(id,"worker"));
            try(var admin=java.sql.DriverManager.getConnection(setting("RELAY_DB_URL"),"root",setting("RELAY_DB_PASSWORD"));var statement=admin.createStatement()) {
                statement.execute("CREATE TRIGGER reject_outcome BEFORE UPDATE ON runs FOR EACH ROW BEGIN IF NEW.run_id='"+id+"' AND NEW.status='succeeded' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'; END IF; END");
                try{assertThatThrownBy(()->store.finish(prepared,new HttpTransport.Outcome(EngineJson.read("{}"),null,false,null))).isInstanceOf(RuntimeException.class);}
                finally{statement.execute("DROP TRIGGER reject_outcome");}
            }
            assertThat(status(sql,id)).isEqualTo("running");
            assertThat(sql.queryForObject("SELECT status FROM steps WHERE run_id=?",String.class,id)).isEqualTo("running");
            assertThat(sql.queryForObject("SELECT status FROM step_attempts WHERE run_id=?",String.class,id)).isEqualTo("running");
            assertThat(sql.queryForObject("SELECT status FROM queue_jobs WHERE run_id=?",String.class,id)).isEqualTo("leased");
            store.finish(prepared,new HttpTransport.Outcome(EngineJson.read("{}"),null,false,null));
            assertThat(status(sql,id)).isEqualTo("succeeded");
        }
    }

}
