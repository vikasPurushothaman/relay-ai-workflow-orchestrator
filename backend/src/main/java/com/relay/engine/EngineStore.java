package com.relay.engine;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** All mutations serialize run -> job -> step -> attempt; transport never runs in these transactions. */
@Component
@ConditionalOnProperty(name="relay.launch.mode")
public class EngineStore {
    public record Claim(String runId,String owner,long generation,String target,Long sequence) {}
    public record Prepared(Claim claim,long attempt,JsonNode request,String key,ExecutionPolicy policy) {}
    public static final class LostLease extends RuntimeException {}
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final TemplateResolver templates=new TemplateResolver();
    private final DestinationPolicy destinations;
    private final String world;
    private final com.relay.ai.AiProvider ai;
    private final com.relay.workflow.OutputSchemaValidator schemas;
    public EngineStore(JdbcTemplate sql,PlatformTransactionManager manager,Environment env,com.relay.ai.AiProvider ai,com.relay.workflow.OutputSchemaValidator schemas) {
        this.ai=ai;this.schemas=schemas;
        this.sql=sql;tx=new TransactionTemplate(manager);
        world=env.getProperty("MOCK_WORLD_URL","http://localhost:9210").replaceAll("/+$","");
        destinations=new DestinationPolicy(env.getProperty("RELAY_HTTP_ALLOWED_ORIGINS",world));
    }
    public <T>T transaction(Supplier<T> work){return tx.execute(status->work.get());}
    private Instant now(){return sql.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,row)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC));}
    private static LocalDateTime date(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    private static Instant instant(Object value) {
        if(value instanceof LocalDateTime at)return at.toInstant(ZoneOffset.UTC);
        return ((java.sql.Timestamp)value).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String text(Map<String,Object> row,String key){return (String)row.get(key);}
    private static Long sequence(Map<String,Object> job){return job.get("step_sequence")==null?null:number(job,"step_sequence");}
    private Map<String,Object> run(String id){return sql.queryForMap("SELECT * FROM runs WHERE run_id=? FOR UPDATE",id);}
    private Map<String,Object> job(String id){return sql.queryForMap("SELECT * FROM queue_jobs WHERE run_id=? FOR UPDATE",id);}
    private Map<String,Object> step(String id,long seq){return sql.queryForMap("SELECT * FROM steps WHERE run_id=? AND sequence=? FOR UPDATE",id,seq);}
    public List<String> candidates(boolean expiredFirst) {
        var ready=sql.queryForList("SELECT run_id FROM queue_jobs WHERE status='ready' AND available_at<=UTC_TIMESTAMP(6) ORDER BY available_at,run_id LIMIT 32",String.class);
        var expired=sql.queryForList("SELECT run_id FROM queue_jobs WHERE status='leased' AND lease_until<=UTC_TIMESTAMP(6) ORDER BY lease_until,run_id LIMIT 32",String.class);
        var result=new ArrayList<String>();
        for(int i=0;i<Math.max(ready.size(),expired.size());i++) {
            var first=expiredFirst?expired:ready;var second=expiredFirst?ready:expired;
            if(i<first.size())result.add(first.get(i));if(i<second.size())result.add(second.get(i));
        }
        return result;
    }
    public Claim claim(String id,String owner) {return transaction(()->{
        var r=run(id);var j=job(id);Instant at=now();
        if(!Set.of("queued","running").contains(text(r,"status")))return null;
        boolean ready="ready".equals(j.get("status")) && !instant(j.get("available_at")).isAfter(at);
        boolean expired="leased".equals(j.get("status")) && !instant(j.get("lease_until")).isAfter(at);
        if(!ready && !expired)return null;
        var policy=ExecutionPolicy.parse(text(r,"execution_policy"));
        long generation=Math.addExact(number(j,"claim_generation"),1);
        sql.update("UPDATE queue_jobs SET status='leased',lease_owner=?,lease_until=?,claim_generation=?,updated_at=? WHERE run_id=?",owner,date(at.plusMillis(policy.leaseMs())),generation,date(at),id);
        sql.update("UPDATE runs SET status='running',started_at=COALESCE(started_at,?),revision=revision+1 WHERE run_id=?",date(at),id);
        return new Claim(id,owner,generation,text(j,"target_node_id"),sequence(j));
    });}
    private void guard(Claim c,Map<String,Object> j,Instant at) {
        if(!"leased".equals(j.get("status")) || !c.owner.equals(j.get("lease_owner")) || c.generation!=number(j,"claim_generation")
            || !c.target.equals(j.get("target_node_id")) || !Objects.equals(c.sequence,sequence(j)) || !instant(j.get("lease_until")).isAfter(at))throw new LostLease();
    }
    private void fence(Claim c,String assignments,Object... values) {
        var args=new ArrayList<>(Arrays.asList(values));
        args.add(c.runId);args.add(c.owner);args.add(c.generation);args.add(c.target);args.add(c.sequence);
        int count=sql.update("UPDATE queue_jobs SET "+assignments+" WHERE run_id=? AND status='leased' AND lease_owner=? AND claim_generation=? AND target_node_id=? AND step_sequence <=> ? AND lease_until>UTC_TIMESTAMP(6)",args.toArray());
        if(count!=1)throw new LostLease();
    }
    public boolean renew(Claim c) {return renewalState(c)==1;}
    /** 1 renewed;2 cancellation observed (let bounded invocation settle);0 ownership lost. */
    public int renewalState(Claim c) {
        try{return transaction(()->{
            var r=run(c.runId);var j=job(c.runId);Instant at=now();guard(c,j,at);
            if(!"running".equals(r.get("status")))return 0;
            if(r.get("cancel_requested_at")!=null)return 2;
            var p=ExecutionPolicy.parse(text(r,"execution_policy"));
            fence(c,"lease_until=?,updated_at=?",date(at.plusMillis(p.leaseMs())),date(at));return 1;
        });}catch(LostLease ex){return 0;}
    }
    public Prepared prepare(Claim original) {return transaction(()->{
        var r=run(original.runId);var j=job(original.runId);Instant at=now();guard(original,j,at);
        var policy=ExecutionPolicy.parse(text(r,"execution_policy"));
        if(r.get("cancel_requested_at")!=null){
            if(original.sequence!=null)sql.update("UPDATE step_attempts SET status='uncertain',error=? WHERE run_id=? AND step_sequence=? AND status='running'",EngineJson.error("unknown_effect"),original.runId,original.sequence);
            if(original.sequence!=null && "ai".equals(step(original.runId,original.sequence).get("node_type")))unknownUsage(original);
            terminate(original,at,"cancelled","cancel_requested");return null;
        }
        if(!"running".equals(r.get("status")))throw new LostLease();
        var definition=EngineJson.read(text(r,"definition_snapshot"));
        JsonNode node=null;Set<String> ids=new HashSet<>();
        for(var n:definition.get("nodes")){ids.add(n.get("id").asString());if(n.get("id").asString().equals(original.target))node=n;}
        if(node==null)throw new NodeFailure("missing_snapshot_node");
        Claim c=original;
        if(c.sequence==null) {
            if(number(r,"steps_executed")>=definition.get("limits").get("max_steps").asLong()) {terminate(c,at,"failed","max_steps");return null;}
            long seq=number(r,"next_step_sequence");
            sql.update("INSERT INTO steps(run_id,sequence,node_id,node_type,status,final_attempt,ai_repair_count,ai_usage_complete,started_at) VALUES(?,?,?,?,'running',0,0,1,?)",c.runId,seq,c.target,node.get("type").asString(),date(at));
            sql.update("UPDATE runs SET steps_executed=steps_executed+1,next_step_sequence=?,revision=revision+1 WHERE run_id=?",Math.addExact(seq,1),c.runId);
            fence(c,"step_sequence=?,updated_at=?",seq,date(at));
            c=new Claim(c.runId,c.owner,c.generation,c.target,seq);
        }
        var s=step(c.runId,c.sequence);String type=text(s,"node_type");
        if("delay".equals(s.get("wait_reason"))) {
            Instant resume=instant(s.get("resume_at"));
            if(resume.isAfter(at)){ready(c,resume,null);return null;}
            completeStep(c,node,EngineJson.JSON.createObjectNode(),at);return null;
        }
        long attempt=number(s,"final_attempt");
        if(attempt>0) {
            var a=sql.queryForMap("SELECT * FROM step_attempts WHERE run_id=? AND step_sequence=? AND attempt_no=? FOR UPDATE",c.runId,c.sequence,attempt);
            if("running".equals(a.get("status"))) {
                if(number(a,"claim_generation")>=c.generation)throw new LostLease();
                sql.update("UPDATE step_attempts SET status='uncertain',error=? WHERE run_id=? AND step_sequence=? AND attempt_no=?",EngineJson.error("unknown_effect"),c.runId,c.sequence,attempt);
                if(type.equals("ai"))unknownUsage(c);
                retry(c,j,policy,at,"recovery","unknown_effect",null);return null;
            }
        }
        try {
            JsonNode resolved;
            if(s.get("resolved_input")==null) {
                final Claim current=c;
                resolved=templates.resolve(type,node.get("params"),EngineJson.read(text(r,"input")),ids,id->{
                    var outputs=sql.queryForList("SELECT output FROM steps WHERE run_id=? AND node_id=? AND sequence<? AND status='succeeded' ORDER BY sequence DESC LIMIT 1",String.class,current.runId,id,current.sequence);
                    return outputs.isEmpty()?null:EngineJson.read(outputs.getFirst());
                });
                sql.update("UPDATE steps SET resolved_input=? WHERE run_id=? AND sequence=?",resolved.toString(),c.runId,c.sequence);
            } else resolved=EngineJson.read(text(s,"resolved_input"));
            if(type.equals("order_action") && sql.queryForObject("SELECT COUNT(*) FROM approvals WHERE run_id=? AND step_sequence<? AND status='approved' AND decided_by IS NOT NULL AND decided_at IS NOT NULL",Long.class,c.runId,c.sequence)==0)
                throw new NodeFailure("approval_required");
            String cause=text(j,"next_attempt_cause");
            if(cause==null || !Set.of("initial","transport_retry","recovery","schema_repair").contains(cause))throw new NodeFailure("invalid_attempt_intent");
            if(!Set.of("initial","schema_repair").contains(cause) && number(j,"retry_count")>=policy.maxAttempts()-1){terminate(c,at,"failed","retry_exhausted");return null;}
            JsonNode request=s.get("dispatch_request")==null?null:EngineJson.read(text(s,"dispatch_request"));
            if(type.equals("ai") && s.get("ai_repair_request")!=null)request=EngineJson.read(text(s,"ai_repair_request"));
            String key=text(s,"idempotency_key");
            if(!Set.of("condition","delay","approval").contains(type) && request==null) {
                request=type.equals("ai")?ai.prepare(resolved):DeterministicNodes.request(type,resolved,world,destinations);
                key=type.equals("ai") || request.get("method").asString().equals("GET")?null:c.runId+":"+c.sequence;
                sql.update("UPDATE steps SET dispatch_request=?,idempotency_key=? WHERE run_id=? AND sequence=?",request.toString(),key,c.runId,c.sequence);
            }
            attempt=Math.addExact(attempt,1);
            sql.update("UPDATE steps SET status='running',wait_reason=NULL,final_attempt=? WHERE run_id=? AND sequence=?",attempt,c.runId,c.sequence);
            sql.update("INSERT INTO step_attempts(run_id,step_sequence,attempt_no,status,cause,claim_generation,request,started_at,provider,model) VALUES(?,?,?,'running',?,?,?,?,?,?)",c.runId,c.sequence,attempt,cause,c.generation,request==null?resolved.toString():request.toString(),date(at),type.equals("ai")?request.get("provider").asString():null,type.equals("ai")?request.get("model").asString():null);
            fence(c,"next_attempt_cause=NULL,retry_count=retry_count+?,updated_at=?",Set.of("initial","schema_repair").contains(cause)?0:1,date(at));
            if(type.equals("approval")) {
                attemptOutcome(c,attempt,"succeeded","{}",null,at);
                sql.update("INSERT INTO approvals(id,run_id,step_sequence,node_id,message,status,created_at) VALUES(?,?,?,?,?,'pending',?)","apr_"+UUID.randomUUID().toString().replace("-",""),c.runId,c.sequence,c.target,resolved.get("message").asString(),date(at));
                sql.update("UPDATE steps SET status='waiting',wait_reason='approval' WHERE run_id=? AND sequence=?",c.runId,c.sequence);
                sql.update("UPDATE runs SET status='waiting_approval',revision=revision+1 WHERE run_id=?",c.runId);
                fence(c,"status='inactive',lease_owner=NULL,lease_until=NULL,next_attempt_cause=NULL,updated_at=?",date(at));return null;
            }
            if(type.equals("condition")) {
                var output=EngineJson.JSON.createObjectNode().put("result",DeterministicNodes.condition(resolved));
                attemptOutcome(c,attempt,"succeeded",output.toString(),null,at);
                completeStep(c,node,output,at);return null;
            }
            if(type.equals("delay")) {
                Instant resume=DeterministicNodes.delay(at,resolved.get("seconds"));
                attemptOutcome(c,attempt,"succeeded","{}",null,at);
                sql.update("UPDATE steps SET status='waiting',wait_reason='delay',resume_at=? WHERE run_id=? AND sequence=?",date(resume),c.runId,c.sequence);
                ready(c,resume,null);return null;
            }
            return new Prepared(c,attempt,request,key,policy);
        } catch(NodeFailure | com.relay.workflow.DefinitionException failure) {
            String code=failure instanceof NodeFailure nf?nf.code:"invalid_template";
            // A local validation failure may occur before an attempt or during local evaluation.
            sql.update("UPDATE step_attempts SET status='failed',error=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND step_sequence=? AND status='running' AND claim_generation=?",EngineJson.error(code),date(at),date(at),c.runId,c.sequence,c.generation);
            terminate(c,at,"failed",code);return null;
        }
    });}
    private void ready(Claim c,Instant due,String cause) {
        fence(c,"status='ready',lease_owner=NULL,lease_until=NULL,available_at=?,next_attempt_cause=?,updated_at=UTC_TIMESTAMP(6)",date(due),cause);
    }
    private void terminate(Claim c,Instant at,String status,String code) {
        var detail=EngineJson.JSON.createObjectNode().put("code",code).put("node_id",c.target);
        if(c.sequence!=null)detail.put("prepared_attempts",number(step(c.runId,c.sequence),"final_attempt"));
        String error=detail.toString();
        if(c.sequence!=null)sql.update("UPDATE steps SET status=?,wait_reason=NULL,error=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND sequence=? AND status IN ('running','waiting')",status,error,date(at),date(at),c.runId,c.sequence);
        sql.update("UPDATE runs SET status=?,error=?,finished_at=?,revision=revision+1 WHERE run_id=?",status,error,date(at),c.runId);
        fence(c,"status='inactive',lease_owner=NULL,lease_until=NULL,next_attempt_cause=NULL,updated_at=?",date(at));
    }
    private void attemptOutcome(Claim c,long attempt,String status,String output,String error,Instant at) {
        int count=sql.update("UPDATE step_attempts SET status=?,output=?,error=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND step_sequence=? AND attempt_no=? AND status='running' AND claim_generation=?",status,output,error,date(at),date(at),c.runId,c.sequence,attempt,c.generation);
        if(count!=1)throw new LostLease();
    }
    private void completeStep(Claim c,JsonNode node,JsonNode output,Instant at) {
        String edge=node.get("type").asString().equals("condition")?(output.get("result").asBoolean()?"on_true":"on_false"):"next";
        String next=node.get(edge).isNull()?null:node.get(edge).asString();
        sql.update("UPDATE steps SET status='succeeded',wait_reason=NULL,output=?,error=NULL,selected_next_node_id=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND sequence=?",output.toString(),next,date(at),date(at),c.runId,c.sequence);
        if(next==null) {
            sql.update("UPDATE runs SET status='succeeded',current_node_id=NULL,finished_at=?,revision=revision+1 WHERE run_id=?",date(at),c.runId);
            fence(c,"status='inactive',lease_owner=NULL,lease_until=NULL,next_attempt_cause=NULL,updated_at=?",date(at));
        } else {
            sql.update("UPDATE runs SET current_node_id=?,revision=revision+1 WHERE run_id=?",next,c.runId);
            fence(c,"status='ready',target_node_id=?,step_sequence=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_cause='initial',retry_count=0,available_at=?,updated_at=?",next,date(at),date(at));
        }
    }
    private void retry(Claim c,Map<String,Object> j,ExecutionPolicy p,Instant at,String cause,String code,Instant hint) {
        if(number(j,"retry_count")>=p.maxAttempts()-1){terminate(c,at,"failed",code.equals("unknown_effect")?"retry_exhausted_unknown_effect":"retry_exhausted");return;}
        Instant due=at.plusMillis(p.backoff(number(j,"retry_count")+1));
        if(hint!=null && hint.isAfter(due))due=hint;
        try{DeterministicNodes.date(due);}catch(NodeFailure ex){terminate(c,at,"failed",ex.code);return;}
        String error=EngineJson.JSON.createObjectNode().put("code",code).put("cause",cause)
            .put("retry_number",number(j,"retry_count")+1).put("delay_ms",Duration.between(at,due).toMillis()).toString();
        sql.update("UPDATE steps SET status='waiting',wait_reason='retry',error=? WHERE run_id=? AND sequence=?",error,c.runId,c.sequence);
        sql.update("UPDATE step_attempts SET error=? WHERE run_id=? AND step_sequence=? AND attempt_no=(SELECT final_attempt FROM steps WHERE run_id=? AND sequence=?)",error,c.runId,c.sequence,c.runId,c.sequence);
        ready(c,due,cause);
    }
    private void unknownUsage(Claim c) {
        sql.update("UPDATE steps SET ai_usage_complete=0 WHERE run_id=? AND sequence=?",c.runId,c.sequence);
        aggregateRunUsage(c,false);
    }
    private Long usageSum(String query,Object... args) {
        var sum=sql.queryForObject(query,java.math.BigDecimal.class,args);
        if(sum==null)return null;
        try{return sum.longValueExact();}catch(ArithmeticException ex){return null;}
    }
    private void aggregateRunUsage(Claim c,boolean known) {
        Long total=usageSum("SELECT SUM(CAST(COALESCE(tokens_prompt,0) AS DECIMAL(65,0))+COALESCE(tokens_completion,0)) FROM step_attempts WHERE run_id=? AND (tokens_prompt IS NOT NULL OR tokens_completion IS NOT NULL)",c.runId);
        sql.update("UPDATE runs SET ai_tokens_used=?,ai_usage_complete=ai_usage_complete AND ?,revision=revision+1 WHERE run_id=?",total,known && total!=null,c.runId);
    }
    private void usage(Prepared p,HttpTransport.Outcome result) {
        var c=p.claim;boolean known=result.tokensPrompt()!=null && result.tokensCompletion()!=null;
        sql.update("UPDATE step_attempts SET tokens_prompt=?,tokens_completion=? WHERE run_id=? AND step_sequence=? AND attempt_no=?",result.tokensPrompt(),result.tokensCompletion(),c.runId,c.sequence,p.attempt);
        Long prompt=usageSum("SELECT SUM(tokens_prompt) FROM step_attempts WHERE run_id=? AND step_sequence=?",c.runId,c.sequence);
        Long completion=usageSum("SELECT SUM(tokens_completion) FROM step_attempts WHERE run_id=? AND step_sequence=?",c.runId,c.sequence);
        sql.update("UPDATE steps SET tokens_prompt=?,tokens_completion=?,ai_usage_complete=ai_usage_complete AND ? WHERE run_id=? AND sequence=?",prompt,completion,known && prompt!=null && completion!=null,c.runId,c.sequence);
        aggregateRunUsage(c,known);
    }
    public void finish(Prepared p,HttpTransport.Outcome result) {transaction(()->{
        var c=p.claim;var r=run(c.runId);var j=job(c.runId);Instant at=now();guard(c,j,at);
        if(!"running".equals(r.get("status")))throw new LostLease();
        var s=step(c.runId,c.sequence);
        if(!"running".equals(s.get("status")) || number(s,"final_attempt")!=p.attempt)throw new LostLease();
        boolean isAi="ai".equals(s.get("node_type")),invalid=false;
        var outcome=result;
        if(isAi && result.success()) {
            JsonNode output=null;String failure="invalid_ai_json";
            try {
                String content=result.output().asString();
                if(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>EngineJson.MAX_BYTES)throw new IllegalArgumentException();
                output=EngineJson.read(content);
                if(output==null || output.isMissingNode())throw new IllegalArgumentException();
                failure="invalid_ai_schema";
                if(!schemas.accepts(p.request.get("output_schema"),output))throw new IllegalArgumentException();
            }catch(RuntimeException ex){invalid=true;}
            outcome=invalid?new HttpTransport.Outcome(null,failure,false,null,result.tokensPrompt(),result.tokensCompletion())
                :new HttpTransport.Outcome(output,null,false,null,result.tokensPrompt(),result.tokensCompletion());
        }
        attemptOutcome(c,p.attempt,outcome.success()?"succeeded":"failed",outcome.output()==null?null:outcome.output().toString(),outcome.success()?null:EngineJson.error(outcome.code()),at);
        if(isAi)usage(p,result);
        if(r.get("cancel_requested_at")!=null){
            sql.update("UPDATE steps SET status=?,wait_reason=NULL,output=?,error=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND sequence=?",outcome.success()?"succeeded":"failed",outcome.output()==null?null:outcome.output().toString(),outcome.success()?null:EngineJson.error(outcome.code()),date(at),date(at),c.runId,c.sequence);
            terminate(c,at,"cancelled","cancel_requested");return null;
        }
        if(invalid) {
            if(number(s,"ai_repair_count")!=0){terminate(c,at,"failed",outcome.code());return null;}
            try {
                JsonNode repair=ai.repair(p.request,outcome.code());
                sql.update("UPDATE steps SET ai_repair_count=1,ai_repair_request=?,status='waiting',wait_reason='retry',error=? WHERE run_id=? AND sequence=?",repair.toString(),EngineJson.error(outcome.code()),c.runId,c.sequence);
                ready(c,at,"schema_repair");
            }catch(NodeFailure ex){terminate(c,at,"failed",ex.code);}
            return null;
        }
        if(!outcome.success()) {
            if(outcome.retryable())retry(c,j,p.policy,at,"transport_retry",outcome.code(),outcome.retryAt(at));
            else terminate(c,at,"failed",outcome.code());
        } else {
            var nodes=EngineJson.read(text(r,"definition_snapshot")).get("nodes");
            for(var node:nodes)if(node.get("id").asString().equals(c.target)){completeStep(c,node,outcome.output(),at);return null;}
            throw new NodeFailure("missing_snapshot_node");
        }
        return null;
    });}
}
