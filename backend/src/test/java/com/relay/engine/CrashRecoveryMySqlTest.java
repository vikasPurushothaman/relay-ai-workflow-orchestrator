package com.relay.engine;

import com.relay.RelayApplication;
import com.relay.api.*;
import com.relay.persistence.TriggerType;
import com.relay.testing.MySqlIntegrationSupport;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class CrashRecoveryMySqlTest extends MySqlIntegrationSupport {
    Process worker(String world,Path log)throws Exception {
        var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","build/libs/relay-backend-0.1.0.jar",
            "--RELAY_MODE=worker","--RELAY_JOB_POLL_MS=20","--MOCK_WORLD_URL="+world,"--RELAY_HTTP_ALLOWED_ORIGINS="+world));
        for(String key:List.of("RELAY_DB_URL","RELAY_DB_USER","RELAY_DB_PASSWORD"))cmd.add("--"+key+"="+setting(key));
        var builder=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(k->k.startsWith("RELAY_") || k.startsWith("SPRING_") || k.startsWith("SERVER_") || k.startsWith("MANAGEMENT_"));
        return builder.start();
    }
    static void stop(Process process)throws Exception {if(process!=null && process.isAlive()){process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
    @Test void killedWorkerReplaysSameFrozenRequestWithoutDuplicatingMockWorldEffect()throws Exception {
        var client=HttpClient.newHttpClient();
        Path worldLog=Files.createTempFile("relay-test-world-",".log"),workerLog=Files.createTempFile("relay-test-worker-",".log");
        int worldPort;try(var socket=new ServerSocket(0)){worldPort=socket.getLocalPort();}
        String world="http://127.0.0.1:"+worldPort;
        var mock=new ProcessBuilder("python3","../scripts/run_mock.py","world","--port",Integer.toString(worldPort)).redirectErrorStream(true).redirectOutput(worldLog.toFile()).start();
        var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool();proxy.setExecutor(executor);
        var effectCommitted=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        proxy.createContext("/",exchange->{
            try {
                var forwarded=HttpRequest.newBuilder(URI.create(world+exchange.getRequestURI())).header("Content-Type","application/json")
                    .header("Idempotency-Key",exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
                var reply=client.send(forwarded,HttpResponse.BodyHandlers.ofByteArray());
                if(first.compareAndSet(true,false)){effectCommitted.countDown();release.await(30,TimeUnit.SECONDS);}
                exchange.sendResponseHeaders(reply.statusCode(),reply.body().length);exchange.getResponseBody().write(reply.body());
            }catch(Exception ignored){}finally{exchange.close();}
        });proxy.start();String target="http://127.0.0.1:"+proxy.getAddress().getPort();
        Process old=null,replacement=null;
        var app=new SpringApplication(RelayApplication.class);app.setRegisterShutdownHook(false);
        try(var context=start(app,"--MOCK_WORLD_URL="+target,"--RELAY_HTTP_ALLOWED_ORIGINS="+target,"--RELAY_RETRY_BASE_MS=10","--RELAY_RETRY_MAX_MS=20")) {
            boolean healthy=false;
            for(int i=0;i<100;i++){try{healthy=client.send(HttpRequest.newBuilder(URI.create(world+"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;if(healthy)break;}catch(Exception ignored){}Thread.sleep(20);}
            assertThat(healthy).isTrue();
            context.getBean(WorkflowService.class).create("""
                {"id":"crash","name":"Crash recovery","trigger":{"type":"manual"},"entry":"a","limits":{"max_steps":1},"nodes":[{"id":"a","type":"notify","params":{"channel":"email","to":"test@example.com","message":"Once"},"next":null}]}
                """);
            context.getBean(PublicationService.class).publish("crash");
            String id=context.getBean(AcceptanceService.class).accept("crash",EngineJson.read("{}"),TriggerType.manual,null);
            old=worker(target,workerLog);
            assertThat(effectCommitted.await(30,TimeUnit.SECONDS)).as(Files.readString(workerLog)).isTrue();
            var sql=context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForObject("SELECT status FROM step_attempts WHERE run_id=? AND attempt_no=1",String.class,id)).isEqualTo("running");
            String frozen=sql.queryForObject("SELECT dispatch_request FROM steps WHERE run_id=?",String.class,id);
            stop(old);release.countDown();
            // Test advances the persisted expiry; replacement still performs the real reclaim/fence protocol.
            sql.update("UPDATE queue_jobs SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE run_id=?",id);
            replacement=worker(target,workerLog);
            String status="";
            for(int i=0;i<300;i++){status=sql.queryForObject("SELECT status FROM runs WHERE run_id=?",String.class,id);if(Set.of("succeeded","failed").contains(status))break;Thread.sleep(50);}
            assertThat(status).as(Files.readString(workerLog)).isEqualTo("succeeded");stop(replacement);
            assertThat(sql.queryForList("SELECT status FROM step_attempts WHERE run_id=? ORDER BY attempt_no",String.class,id)).containsExactly("uncertain","succeeded");
            assertThat(sql.queryForObject("SELECT steps_executed FROM runs WHERE run_id=?",Long.class,id)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT dispatch_request FROM steps WHERE run_id=?",String.class,id)).isEqualTo(frozen);
            var response=client.send(HttpRequest.newBuilder(URI.create(world+"/admin/ledger")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var ledger=EngineJson.read(response.body());
            var entries=ledger.isArray()?ledger:ledger.get("entries");
            assertThat(entries.size()).isEqualTo(2);
            assertThat(entries.get(0).get("idempotency_key").asString()).isEqualTo(id+":1");
            assertThat(entries.get(1).get("idempotency_key").asString()).isEqualTo(id+":1");
            assertThat(entries.get(0).get("replayed").asBoolean()).isFalse();assertThat(entries.get(1).get("replayed").asBoolean()).isTrue();
            // Phase5's human API is not implemented yet. Seed its valid earlier-decision state
            // explicitly as a test fixture, then exercise both real catalog action adapters.
            context.getBean(WorkflowService.class).create("""
                {"id":"actions","name":"Action adapters","trigger":{"type":"manual"},"entry":"gate","limits":{"max_steps":3},"nodes":[
                  {"id":"gate","type":"approval","params":{"message":"Approve"},"next":"refund"},
                  {"id":"refund","type":"order_action","params":{"action":"refund","order_id":"ord_2002","amount_usd":1.5},"next":"replace"},
                  {"id":"replace","type":"order_action","params":{"action":"replacement","order_id":"ord_2003"},"next":null}]}
                """);
            context.getBean(PublicationService.class).publish("actions");
            String actionRun=context.getBean(AcceptanceService.class).accept("actions",EngineJson.read("{}"),TriggerType.manual,null);
            var store=context.getBean(EngineStore.class);
            store.transaction(()->{
                sql.update("INSERT INTO steps(run_id,sequence,node_id,node_type,status,final_attempt,ai_repair_count,ai_usage_complete,started_at,finished_at,duration_ms,output) VALUES(?,1,'gate','approval','succeeded',1,0,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0,'{\"decision\":\"approved\",\"decided_by\":\"demo-operator\"}')",actionRun);
                sql.update("INSERT INTO step_attempts(run_id,step_sequence,attempt_no,status,cause,claim_generation,started_at,finished_at,duration_ms) VALUES(?,1,1,'succeeded','initial',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",actionRun);
                sql.update("INSERT INTO approvals(id,run_id,step_sequence,node_id,message,status,created_at,decided_at,decided_by) VALUES('fixture-approval',?,1,'gate','Approve','approved',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'demo-operator')",actionRun);
                sql.update("UPDATE runs SET status='running',current_node_id='refund',steps_executed=1,next_step_sequence=2,started_at=UTC_TIMESTAMP(6),revision=revision+1 WHERE run_id=?",actionRun);
                sql.update("UPDATE queue_jobs SET target_node_id='refund' WHERE run_id=?",actionRun);return null;
            });
            for(int visit=0;visit<2;visit++) {
                var prepared=store.prepare(store.claim(actionRun,"adapter-test"));
                assertThat(prepared).isNotNull();
                var outcome=context.getBean(HttpTransport.class).send(prepared,new DestinationPolicy(target));
                assertThat(outcome.success()).isTrue();assertThat(outcome.output().get("reference_id").asString()).isNotBlank();
                store.finish(prepared,outcome);
            }
            assertThat(sql.queryForObject("SELECT status FROM runs WHERE run_id=?",String.class,actionRun)).isEqualTo("succeeded");
            var ledgerAfter=EngineJson.read(client.send(HttpRequest.newBuilder(URI.create(world+"/admin/ledger")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).get("entries");
            assertThat(ledgerAfter.size()).isEqualTo(4);
            assertThat(ledgerAfter.get(2).get("idempotency_key").asString()).isEqualTo(actionRun+":2");
            assertThat(ledgerAfter.get(3).get("idempotency_key").asString()).isEqualTo(actionRun+":3");
        } finally {
            release.countDown();stop(old);stop(replacement);proxy.stop(0);executor.shutdownNow();stop(mock);
            Files.deleteIfExists(worldLog);Files.deleteIfExists(workerLog);
        }
    }
}
