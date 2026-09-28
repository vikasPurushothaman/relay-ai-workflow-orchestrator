package com.relay.workflow;

import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pinned pack metadata; possession of metadata does not implement a handler. */
@Component
public final class NodeCatalog {
    private final JsonNode catalog;
    private final Map<String,JsonNode> nodes;
    public NodeCatalog() {
        try(var stream=NodeCatalog.class.getResourceAsStream("/catalog/node_catalog.json")) {
            if(stream==null) throw new IllegalStateException("Missing node catalog");
            catalog=new JsonMapper().readTree(stream);
            var index=new LinkedHashMap<String,JsonNode>();
            catalog.get("nodes").forEach(node -> index.put(node.get("type").asString(),node));
            nodes=Collections.unmodifiableMap(index);
        } catch(IOException e) { throw new IllegalStateException("Cannot load node catalog"); }
    }
    public Set<String> supportedTriggers() { return Set.of("manual","webhook"); }
    public List<String> types() { return List.copyOf(nodes.keySet()); }
    public Optional<JsonNode> node(String type) { return Optional.ofNullable(nodes.get(type)).map(JsonNode::deepCopy); }
    public JsonNode source() { return catalog.deepCopy(); }
}
