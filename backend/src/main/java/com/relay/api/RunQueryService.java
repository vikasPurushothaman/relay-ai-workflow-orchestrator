package com.relay.api;

import com.relay.engine.EngineJson;
import com.relay.security.ManagementAuthentication;
import java.nio.charset.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/** Read-only run projections. Payload JSON passes through {@link TraceRedactor}; the frozen snapshot and dispatch requests are never returned. */
@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class RunQueryService {
    public static final int DEFAULT_LIMIT=25, MAX_LIMIT=100, DEFAULT_STEPS_LIMIT=500, MAX_STEPS_LIMIT=500;
    static final Set<String> STATUSES=Set.of("queued","running","waiting_approval","succeeded","failed","cancelled");
    public record RunSummary(String run_id,String workflow_id,String status,String trigger_type,String current_node_id,
        long steps_executed,Long max_steps,Instant created_at,Instant started_at,Instant finished_at) {}
    public record RunPage(List<RunSummary> runs,String next_cursor) {}
    public record RunError(String code,String node_id) {}
    public record AttemptView(long attempt_no,String status,String cause,JsonNode error,JsonNode output,String provider,String model,
        Long tokens_prompt,Long tokens_completion,Instant started_at,Instant finished_at,Long duration_ms) {}
    public record ApprovalEvidence(String id,String status,String message,String decided_by,Instant decided_at,Instant closed_at,String close_reason) {}
    public record StepView(long sequence,String node_id,String node_type,String status,String wait_reason,long attempt_count,
        String selected_next_node_id,Instant resume_at,Instant started_at,Instant finished_at,Long duration_ms,
        JsonNode resolved_input,JsonNode output,JsonNode error,String idempotency_key,long ai_repair_count,
        Long tokens_prompt,Long tokens_completion,boolean ai_usage_complete,Instant retry_due_at,
        ApprovalEvidence approval,List<AttemptView> attempts) {}
    public record RunDetail(String run_id,String workflow_id,String workflow_name,String entry,String status,String trigger_type,String current_node_id,
        long steps_executed,Long max_steps,Instant created_at,Instant started_at,Instant finished_at,RunError error,
        Instant cancel_requested_at,String cancel_requested_by,String cancellation_reason,
        JsonNode input,Long ai_tokens_used,boolean ai_usage_complete,
        List<StepView> steps,Long steps_next_after) {}
    record Cursor(Instant createdAt,String runId) {}
    record ListQuery(String workflowId,String status,int limit,Cursor cursor) {}
    record StepQuery(long after,int limit) {}

    private final JdbcTemplate sql;
    public RunQueryService(JdbcTemplate sql){this.sql=sql;}

    @Transactional(readOnly=true)
    public RunPage list(MultiValueMap<String,String> params) {
        ManagementAuthentication.requireActor();
        var q=listQuery(params);
        var where=new ArrayList<String>();var args=new ArrayList<Object>();
        if(q.workflowId()!=null){where.add("workflow_id=?");args.add(q.workflowId());}
        if(q.status()!=null){where.add("status=?");args.add(q.status());}
        if(q.cursor()!=null) {
            var at=date(q.cursor().createdAt());
            where.add("(created_at<? OR (created_at=? AND run_id<?))");args.addAll(List.of(at,at,q.cursor().runId()));
        }
        args.add(q.limit()+1);
        var rows=sql.query("SELECT run_id,workflow_id,status,trigger_type,current_node_id,steps_executed,"
            +"CAST(JSON_EXTRACT(definition_snapshot,'$.limits.max_steps') AS SIGNED) AS max_steps,created_at,started_at,finished_at FROM runs"
            +(where.isEmpty()?"":" WHERE "+String.join(" AND ",where))+" ORDER BY created_at DESC,run_id DESC LIMIT ?",
            (rs,n)->new RunSummary(rs.getString("run_id"),rs.getString("workflow_id"),rs.getString("status"),rs.getString("trigger_type"),
                rs.getString("current_node_id"),rs.getLong("steps_executed"),nullableLong(rs,"max_steps"),time(rs,"created_at"),time(rs,"started_at"),time(rs,"finished_at")),args.toArray());
        if(rows.size()<=q.limit())return new RunPage(rows,null);
        var page=rows.subList(0,q.limit());var last=page.getLast();
        return new RunPage(List.copyOf(page),encode(new Cursor(last.created_at(),last.run_id())));
    }

    private record RunRow(RunDetail detail,String secret) {}
    @Transactional(readOnly=true)
    public RunDetail get(String runId,MultiValueMap<String,String> params) {
        ManagementAuthentication.requireActor();
        var q=stepQuery(params);
        if(!validId(runId))throw new ApiFailure(ApiFailure.Reason.NOT_FOUND);
        // The snapshot is read only for display metadata and the secret to redact; it is never returned.
        var runs=sql.query("SELECT run_id,workflow_id,status,trigger_type,current_node_id,steps_executed,created_at,started_at,finished_at,input,ai_tokens_used,ai_usage_complete,"
            +"CAST(JSON_EXTRACT(definition_snapshot,'$.limits.max_steps') AS SIGNED) AS max_steps,"
            +"JSON_UNQUOTE(JSON_EXTRACT(definition_snapshot,'$.name')) AS workflow_name,JSON_UNQUOTE(JSON_EXTRACT(definition_snapshot,'$.entry')) AS entry,"
            +"JSON_UNQUOTE(JSON_EXTRACT(definition_snapshot,'$.trigger.secret')) AS secret,"
            +"JSON_UNQUOTE(JSON_EXTRACT(error,'$.code')) AS error_code,JSON_UNQUOTE(JSON_EXTRACT(error,'$.node_id')) AS error_node,"
            +"cancel_requested_at,cancel_requested_by,cancellation_reason FROM runs WHERE run_id=?",(rs,n)->new RunRow(new RunDetail(
                rs.getString("run_id"),rs.getString("workflow_id"),rs.getString("workflow_name"),rs.getString("entry"),rs.getString("status"),rs.getString("trigger_type"),
                rs.getString("current_node_id"),rs.getLong("steps_executed"),nullableLong(rs,"max_steps"),time(rs,"created_at"),time(rs,"started_at"),time(rs,"finished_at"),
                rs.getString("error_code")==null?null:new RunError(rs.getString("error_code"),nullText(rs.getString("error_node"))),
                time(rs,"cancel_requested_at"),rs.getString("cancel_requested_by"),rs.getString("cancellation_reason"),
                json(rs,"input"),nullableLong(rs,"ai_tokens_used"),rs.getBoolean("ai_usage_complete"),null,null),rs.getString("secret")),runId);
        if(runs.isEmpty())throw new ApiFailure(ApiFailure.Reason.NOT_FOUND);
        var r=runs.getFirst().detail();var redactor=new TraceRedactor(runs.getFirst().secret());
        // Same read-only transaction as the run row, so status and steps describe one consistent snapshot.
        var rows=sql.queryForList("SELECT `sequence`,node_id,node_type,status,wait_reason,final_attempt,selected_next_node_id,resume_at,started_at,finished_at,duration_ms,"
            +"resolved_input,output,error,idempotency_key,ai_repair_count,tokens_prompt,tokens_completion,ai_usage_complete"
            +" FROM steps WHERE run_id=? AND `sequence`>? ORDER BY `sequence` LIMIT ?",runId,q.after(),q.limit()+1);
        Long next=null;
        if(rows.size()>q.limit()){rows=rows.subList(0,q.limit());next=number(rows.getLast().get("sequence"));}
        var attempts=new HashMap<Long,List<AttemptView>>();var approvals=new HashMap<Long,ApprovalEvidence>();Long retryDueSequence=null;Instant retryDue=null;
        if(!rows.isEmpty()) {
            long first=number(rows.getFirst().get("sequence")),last=number(rows.getLast().get("sequence"));
            sql.query("SELECT * FROM step_attempts WHERE run_id=? AND step_sequence BETWEEN ? AND ? ORDER BY step_sequence,attempt_no",rs->{
                attempts.computeIfAbsent(rs.getLong("step_sequence"),k->new ArrayList<>()).add(new AttemptView(rs.getLong("attempt_no"),rs.getString("status"),rs.getString("cause"),
                    redactor.redact(json(rs,"error")),redactor.redact(json(rs,"output")),rs.getString("provider"),rs.getString("model"),
                    nullableLong(rs,"tokens_prompt"),nullableLong(rs,"tokens_completion"),time(rs,"started_at"),time(rs,"finished_at"),nullableLong(rs,"duration_ms")));
            },runId,first,last);
            sql.query("SELECT * FROM approvals WHERE run_id=? AND step_sequence BETWEEN ? AND ?",rs->{
                approvals.put(rs.getLong("step_sequence"),new ApprovalEvidence(rs.getString("id"),rs.getString("status"),redactor.text(rs.getString("message")),
                    rs.getString("decided_by"),time(rs,"decided_at"),time(rs,"closed_at"),rs.getString("close_reason")));
            },runId,first,last);
            var job=sql.query("SELECT step_sequence,available_at FROM queue_jobs WHERE run_id=? AND status='ready' AND step_sequence IS NOT NULL",
                (rs,n)->new Object[]{rs.getLong("step_sequence"),time(rs,"available_at")},runId);
            if(!job.isEmpty()){retryDueSequence=(Long)job.getFirst()[0];retryDue=(Instant)job.getFirst()[1];}
        }
        var steps=new ArrayList<StepView>();
        for(var s:rows) {
            long seq=number(s.get("sequence"));String wait=(String)s.get("wait_reason");
            steps.add(new StepView(seq,(String)s.get("node_id"),(String)s.get("node_type"),(String)s.get("status"),wait,number(s.get("final_attempt")),
                (String)s.get("selected_next_node_id"),instant(s.get("resume_at")),instant(s.get("started_at")),instant(s.get("finished_at")),
                s.get("duration_ms")==null?null:number(s.get("duration_ms")),redactor.redact(json(s.get("resolved_input"))),redactor.redact(json(s.get("output"))),
                redactor.redact(json(s.get("error"))),(String)s.get("idempotency_key"),number(s.get("ai_repair_count")),
                s.get("tokens_prompt")==null?null:number(s.get("tokens_prompt")),s.get("tokens_completion")==null?null:number(s.get("tokens_completion")),
                bool(s.get("ai_usage_complete")),"retry".equals(wait) && Objects.equals(retryDueSequence,seq)?retryDue:null,
                approvals.get(seq),attempts.getOrDefault(seq,List.of())));
        }
        return new RunDetail(r.run_id(),r.workflow_id(),r.workflow_name(),r.entry(),r.status(),r.trigger_type(),r.current_node_id(),r.steps_executed(),r.max_steps(),
            r.created_at(),r.started_at(),r.finished_at(),r.error(),r.cancel_requested_at(),r.cancel_requested_by(),r.cancellation_reason(),
            redactor.redact(r.input()),r.ai_tokens_used(),r.ai_usage_complete(),steps,next);
    }
    private static long number(Object value){return ((Number)value).longValue();}
    private static boolean bool(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private static JsonNode json(Object value){return value==null?null:EngineJson.read(value.toString());}
    private static JsonNode json(ResultSet rs,String name)throws SQLException {return json(rs.getString(name));}
    private static Instant instant(Object value) {
        if(value==null)return null;
        if(value instanceof LocalDateTime d)return d.toInstant(ZoneOffset.UTC);
        if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected timestamp type");
    }

    static ListQuery listQuery(MultiValueMap<String,String> params) {
        allow(params,Set.of("workflow_id","status","limit","cursor"));
        String workflow=single(params,"workflow_id");
        if(workflow!=null && !validId(workflow))throw invalid();
        String status=single(params,"status");
        if(status!=null && !STATUSES.contains(status))throw invalid();
        String cursor=single(params,"cursor");
        return new ListQuery(workflow,status,integer(single(params,"limit"),DEFAULT_LIMIT,1,MAX_LIMIT),cursor==null?null:decode(cursor));
    }
    static StepQuery stepQuery(MultiValueMap<String,String> params) {
        allow(params,Set.of("steps_after","steps_limit"));
        String after=single(params,"steps_after");
        long from=0;
        if(after!=null) {
            if(!after.matches("[0-9]{1,18}"))throw invalid();
            from=Long.parseLong(after);
        }
        return new StepQuery(from,integer(single(params,"steps_limit"),DEFAULT_STEPS_LIMIT,1,MAX_STEPS_LIMIT));
    }

    /** Opaque to clients: base64url(no padding) of "createdAtEpochMicros:runId". */
    static String encode(Cursor c) {
        long micros=Math.addExact(Math.multiplyExact(c.createdAt().getEpochSecond(),1_000_000L),c.createdAt().getNano()/1000);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((micros+":"+c.runId()).getBytes(StandardCharsets.UTF_8));
    }
    static Cursor decode(String text) {
        if(text.length()>1024 || !text.matches("[A-Za-z0-9_-]+"))throw invalid();
        try {
            byte[] bytes=Base64.getUrlDecoder().decode(text);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text))throw invalid();
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            int split=value.indexOf(':');
            if(split<1)throw invalid();
            String micros=value.substring(0,split),runId=value.substring(split+1);
            if(!micros.matches("[0-9]{1,18}") || !validId(runId))throw invalid();
            long m=Long.parseLong(micros);
            var at=Instant.ofEpochSecond(m/1_000_000L,(m%1_000_000L)*1000L);
            if(at.isAfter(LATEST))throw invalid(); // Beyond MySQL DATETIME; would otherwise surface as a storage error.
            return new Cursor(at,runId);
        } catch(IllegalArgumentException | CharacterCodingException e) {
            throw invalid();
        }
    }
    private static final Instant LATEST=Instant.parse("9999-12-31T23:59:59.999999Z");

    private static void allow(MultiValueMap<String,String> params,Set<String> names) {
        for(var e:params.entrySet())if(!names.contains(e.getKey()) || e.getValue().size()!=1)throw invalid();
    }
    /** Empty values mean "not supplied", matching how HTML forms submit cleared filters. */
    private static String single(MultiValueMap<String,String> params,String name) {
        String value=params.getFirst(name);return value==null || value.isEmpty()?null:value;
    }
    private static int integer(String text,int fallback,int min,int max) {
        if(text==null)return fallback;
        if(!text.matches("[0-9]{1,9}"))throw invalid();
        int value=Integer.parseInt(text);
        if(value<min || value>max)throw invalid();
        return value;
    }
    private static boolean validId(String value){return new OpaqueIdValidator().isValid(value,null);}
    private static ApiFailure invalid(){return new ApiFailure(ApiFailure.Reason.INVALID_INPUT);}
    private static String nullText(String value){return value==null || "null".equals(value)?null:value;}
    private static Long nullableLong(ResultSet rs,String name)throws SQLException {long v=rs.getLong(name);return rs.wasNull()?null:v;}
    private static LocalDateTime date(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    private static Instant time(ResultSet rs,String name)throws SQLException {
        var value=rs.getObject(name,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
}
