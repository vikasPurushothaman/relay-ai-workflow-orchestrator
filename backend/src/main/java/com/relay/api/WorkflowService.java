package com.relay.api;

import com.relay.persistence.*;
import com.relay.workflow.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class WorkflowService {
    private final WorkflowRepository repository;
    private final DefinitionParser parser;
    private static final JsonMapper json=new JsonMapper();
    public WorkflowService(WorkflowRepository repository, DefinitionParser parser) {
        this.repository=repository; this.parser=parser;
    }
    public record Summary(String id,String name,WorkflowStatus status,String trigger_type,Instant updated_at) {}
    public record Detail(String id,String name,String description,WorkflowStatus status,JsonNode definition,
        boolean secret_configured,Instant created_at,Instant updated_at,Instant published_at,
        JsonNode published_definition,boolean published_secret_configured) {}
    @Transactional(readOnly=true)
    public List<Summary> list() {
        return repository.findAll(org.springframework.data.domain.Sort.by("id")).stream().map(w ->
            new Summary(w.getId(),w.getName(),w.getStatus(),json.readTree(w.getDraftDefinition()).path("trigger").path("type").asString(),w.getUpdatedAt())).toList();
    }
    @Transactional(readOnly=true)
    public Detail get(String id) { return detail(repository.findById(id).orElseThrow(WorkflowService::missing)); }
    @Transactional
    public Detail create(String source) {
        var definition=parser.parse(source);
        if(repository.existsById(definition.id())) throw new ApiFailure(ApiFailure.Reason.CONFLICT);
        JsonNode tree=definition.json();
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var workflow=new Workflow(definition.id(),tree.get("name").asString(),WorkflowStatus.draft,tree.toString(),now,now);
        workflow.replaceDraft(workflow.getName(),description(tree),tree.toString(),now);
        repository.saveAndFlush(workflow);
        return detail(workflow);
    }
    @Transactional
    public Detail update(String id,String source) {
        var definition=parser.parse(source);
        if(!id.equals(definition.id())) throw new DefinitionException("immutable_id","$.id");
        var workflow=repository.lockById(id).orElseThrow(WorkflowService::missing);
        var tree=definition.json();
        workflow.replaceDraft(tree.get("name").asString(),description(tree),tree.toString(),Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.flush();
        return detail(workflow);
    }
    static Detail detail(Workflow w) {
        JsonNode draft=json.readTree(w.getDraftDefinition());
        JsonNode published=w.getPublishedDefinition()==null?null:json.readTree(w.getPublishedDefinition());
        boolean secret=draft.path("trigger").has("secret");
        boolean publishedSecret=published!=null && published.path("trigger").has("secret");
        redact(draft);
        if(published!=null) redact(published);
        return new Detail(w.getId(),w.getName(),w.getDescription(),w.getStatus(),draft,secret,w.getCreatedAt(),w.getUpdatedAt(),w.getPublishedAt(),published,publishedSecret);
    }
    private static void redact(JsonNode tree) {
        if(tree.get("trigger") instanceof ObjectNode trigger) trigger.remove("secret");
    }
    private static String description(JsonNode tree) { return tree.has("description")?tree.get("description").asString():null; }
    private static ApiFailure missing() { return new ApiFailure(ApiFailure.Reason.NOT_FOUND); }
}
