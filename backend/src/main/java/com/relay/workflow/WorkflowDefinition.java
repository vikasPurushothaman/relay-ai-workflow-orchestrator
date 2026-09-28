package com.relay.workflow;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** A structurally valid draft, not a published or executable graph. */
public final class WorkflowDefinition {
    private final JsonNode json;
    private final Map<String,JsonNode> nodes;
    WorkflowDefinition(JsonNode source) {
        json=source.deepCopy();
        var index=new LinkedHashMap<String,JsonNode>();
        json.get("nodes").forEach(node -> index.put(node.get("id").asString(), node));
        nodes=Collections.unmodifiableMap(index);
    }
    public String id() { return json.get("id").asString(); }
    public String entry() { return json.get("entry").asString(); }
    public JsonNode json() { return json.deepCopy(); }
    public List<String> nodeIds() { return List.copyOf(nodes.keySet()); }
    public Optional<JsonNode> node(String id) { return Optional.ofNullable(nodes.get(id)).map(JsonNode::deepCopy); }
    @Override public String toString() { return "WorkflowDefinition[redacted]"; }
}
