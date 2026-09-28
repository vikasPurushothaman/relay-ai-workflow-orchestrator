package com.relay.workflow;

import com.relay.api.ApiFailure;
import com.relay.persistence.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Internal acceptance building block. Caller must authenticate, validate policy, and persist run+job
 * in the SAME transaction. This does not accept a trigger, save a run or enqueue any work. */
@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class RunSnapshotFactory {
    private final WorkflowRepository repository;
    private final DefinitionParser parser;
    public RunSnapshotFactory(WorkflowRepository repository,DefinitionParser parser) { this.repository=repository;this.parser=parser; }
    @Transactional(propagation=Propagation.MANDATORY)
    public Run prepare(String runId,String workflowId,JsonNode policy,JsonNode input,TriggerType trigger,Instant at) {
        Objects.requireNonNull(policy);Objects.requireNonNull(input);Objects.requireNonNull(trigger);Objects.requireNonNull(at);
        if(!policy.isObject() || (trigger==TriggerType.manual && !input.isObject())) throw new ApiFailure(ApiFailure.Reason.INVALID_INPUT);
        var w=repository.lockById(workflowId).orElseThrow(()->new ApiFailure(ApiFailure.Reason.NOT_FOUND));
        if(w.getStatus()!=WorkflowStatus.published || w.getPublishedDefinition()==null) throw new ApiFailure(ApiFailure.Reason.NOT_PUBLISHED);
        var definition=parser.parse(w.getPublishedDefinition());
        if(trigger==TriggerType.webhook && !definition.json().get("trigger").get("type").asString().equals("webhook"))
            throw new ApiFailure(ApiFailure.Reason.INVALID_TRIGGER);
        return Run.queuedSnapshot(runId,workflowId,w.getPublishedDefinition(),policy.toString(),input.toString(),trigger,definition.entry(),at);
    }
}
