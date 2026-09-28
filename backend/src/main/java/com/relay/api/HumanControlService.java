package com.relay.api;

import com.relay.engine.EngineJson;
import com.relay.security.ManagementAuthentication;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human decisions and cancellation serialize with the worker on run -> job -> step -> approval. */
@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class HumanControlService {
    public record ApprovalView(String id,String run_id,String workflow_id,long step_sequence,String node_id,String message,String status,Instant created_at,String decided_by,Instant decided_at,Instant closed_at) {}
    public record RunResult(String run_id,String status) {}
    private final JdbcTemplate sql;
    public HumanControlService(JdbcTemplate sql){this.sql=sql;}
    private Instant now(){return sql.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC));}
    private static LocalDateTime date(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    private Map<String,Object> row(String query,Object... args) {
        var rows=sql.queryForList(query,args);
        if(rows.isEmpty())throw new ApiFailure(ApiFailure.Reason.NOT_FOUND);return rows.getFirst();
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    @Transactional(readOnly=true)
    public List<ApprovalView> list(String status) {
        ManagementAuthentication.requireActor();
        if(!Set.of("pending","approved","rejected","closed").contains(status))throw new ApiFailure(ApiFailure.Reason.INVALID_INPUT);
        return sql.query("SELECT a.*,r.workflow_id FROM approvals a JOIN runs r ON r.run_id=a.run_id WHERE a.status=? AND (?<>'pending' OR (a.closed_at IS NULL AND r.status='waiting_approval')) ORDER BY a.created_at,a.id",
            (rs,n)->new ApprovalView(rs.getString("id"),rs.getString("run_id"),rs.getString("workflow_id"),rs.getLong("step_sequence"),rs.getString("node_id"),rs.getString("message"),rs.getString("status"),time(rs,"created_at"),rs.getString("decided_by"),time(rs,"decided_at"),time(rs,"closed_at")),status,status);
    }
    private static Instant time(java.sql.ResultSet rs,String name)throws java.sql.SQLException {
        var value=rs.getObject(name,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
    @Transactional
    public RunResult decide(String id,boolean approve) {
        String actor=ManagementAuthentication.requireActor();
        String runId=(String)row("SELECT run_id FROM approvals WHERE id=?",id).get("run_id");
        var run=row("SELECT * FROM runs WHERE run_id=? FOR UPDATE",runId);
        var job=row("SELECT * FROM queue_jobs WHERE run_id=? FOR UPDATE",runId);
        var hint=row("SELECT step_sequence FROM approvals WHERE id=?",id);long seq=number(hint,"step_sequence");
        var step=row("SELECT * FROM steps WHERE run_id=? AND sequence=? FOR UPDATE",runId,seq);
        var approval=row("SELECT * FROM approvals WHERE id=? FOR UPDATE",id);
        if(!"pending".equals(approval.get("status")) || !"waiting_approval".equals(run.get("status")) || run.get("cancel_requested_at")!=null
            || !"inactive".equals(job.get("status")) || job.get("step_sequence")==null || number(job,"step_sequence")!=seq
            || !"waiting".equals(step.get("status")) || !"approval".equals(step.get("wait_reason")))throw new ApiFailure(ApiFailure.Reason.CONFLICT);
        Instant at=now();String decision=approve?"approved":"rejected";
        sql.update("UPDATE approvals SET status=?,decided_by=?,decided_at=? WHERE id=?",decision,actor,date(at),id);
        var output=EngineJson.JSON.createObjectNode().put("decision",decision).put("decided_by",actor);
        String next=null;
        if(approve)for(var node:EngineJson.read((String)run.get("definition_snapshot")).get("nodes"))if(node.get("id").asString().equals(step.get("node_id")))next=node.get("next").isNull()?null:node.get("next").asString();
        sql.update("UPDATE steps SET status='succeeded',wait_reason=NULL,output=?,selected_next_node_id=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND sequence=?",output.toString(),next,date(at),date(at),runId,seq);
        if(approve && next!=null) {
            sql.update("UPDATE runs SET status='running',current_node_id=?,revision=revision+1 WHERE run_id=?",next,runId);
            sql.update("UPDATE queue_jobs SET status='ready',step_sequence=NULL,target_node_id=?,next_attempt_cause='initial',retry_count=0,available_at=?,updated_at=? WHERE run_id=?",next,date(at),date(at),runId);
            return new RunResult(runId,"running");
        }
        String status=approve?"succeeded":"cancelled";
        sql.update("UPDATE runs SET status=?,current_node_id=NULL,finished_at=?,cancellation_reason=?,revision=revision+1 WHERE run_id=?",status,date(at),approve?null:"approval_rejected",runId);
        sql.update("UPDATE queue_jobs SET next_attempt_cause=NULL,updated_at=? WHERE run_id=?",date(at),runId);
        return new RunResult(runId,status);
    }
    @Transactional
    public RunResult cancel(String id) {
        String actor=ManagementAuthentication.requireActor();
        var run=row("SELECT * FROM runs WHERE run_id=? FOR UPDATE",id);
        var job=row("SELECT * FROM queue_jobs WHERE run_id=? FOR UPDATE",id);Instant at=now();
        if(Set.of("succeeded","failed","cancelled").contains(run.get("status")))throw new ApiFailure(ApiFailure.Reason.CONFLICT);
        sql.update("UPDATE runs SET cancel_requested_at=COALESCE(cancel_requested_at,?),cancel_requested_by=COALESCE(cancel_requested_by,?),cancellation_reason=COALESCE(cancellation_reason,'operator_cancelled'),revision=revision+1 WHERE run_id=?",date(at),actor,id);
        // A leased invocation may already be in flight; its bounded result or recovery settles it.
        if("leased".equals(job.get("status")))return new RunResult(id,"running");
        if(job.get("step_sequence")!=null) {
            long seq=number(job,"step_sequence");row("SELECT * FROM steps WHERE run_id=? AND sequence=? FOR UPDATE",id,seq);
            sql.update("UPDATE steps SET status='cancelled',wait_reason=NULL,error=?,finished_at=?,duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,?)/1000) WHERE run_id=? AND sequence=? AND status IN ('running','waiting')",EngineJson.error("cancel_requested"),date(at),date(at),id,seq);
            sql.update("UPDATE approvals SET status='closed',closed_at=?,close_reason='run_cancelled' WHERE run_id=? AND status='pending'",date(at),id);
        }
        sql.update("UPDATE runs SET status='cancelled',finished_at=?,revision=revision+1 WHERE run_id=?",date(at),id);
        sql.update("UPDATE queue_jobs SET status='inactive',next_attempt_cause=NULL,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE run_id=?",date(at),id);
        return new RunResult(id,"cancelled");
    }
}
