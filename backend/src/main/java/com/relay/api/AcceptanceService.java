package com.relay.api;

import com.relay.engine.*;
import com.relay.persistence.*;
import com.relay.workflow.RunSnapshotFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class AcceptanceService {
    private final RunSnapshotFactory snapshots;
    private final RunRepository runs;
    private final QueueJobRepository jobs;
    private final ExecutionPolicy policy;
    public AcceptanceService(RunSnapshotFactory snapshots,RunRepository runs,QueueJobRepository jobs,Environment environment) {
        this.snapshots=snapshots;this.runs=runs;this.jobs=jobs;this.policy=ExecutionPolicy.from(environment);
    }
    @Transactional
    public String accept(String workflowId,JsonNode input,TriggerType type,String secret) {
        var at=Instant.now();
        String id="run_"+UUID.randomUUID().toString().replace("-","");
        var run=snapshots.prepare(id,workflowId,policy.json(),input,type,at);
        if(type==TriggerType.webhook) {
            String expected=EngineJson.read(run.getDefinitionSnapshot()).get("trigger").get("secret").asString();
            if(secret==null || !MessageDigest.isEqual(hash(expected),hash(secret)))throw new ApiFailure(ApiFailure.Reason.WEBHOOK_AUTH);
        }
        runs.saveAndFlush(run);
        jobs.saveAndFlush(QueueJob.initial(id,run.getCurrentNodeId(),at));
        return id;
    }
    private static byte[] hash(String text) {
        try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
