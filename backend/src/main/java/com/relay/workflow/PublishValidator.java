package com.relay.workflow;

import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Stateless admission checks. Validating does not publish, resolve templates or dispatch effects. */
@Component
public final class PublishValidator {
    private final NodeCatalog catalog;
    private final OutputSchemaValidator schemas;
    public PublishValidator(NodeCatalog catalog,OutputSchemaValidator schemas) { this.catalog=catalog; this.schemas=schemas; }
    public void validate(WorkflowDefinition definition) {
        Set<String> ids=new HashSet<>(definition.nodeIds());
        if(!ids.contains(definition.entry())) fail("invalid_entry","$.entry");
        int index=0;
        for(JsonNode node:definition.json().get("nodes")) {
            String path="$.nodes["+(index++)+"]";
            String type=node.get("type").asString();
            var spec=catalog.node(type).orElseThrow(()->new DefinitionException("invalid_node_type",path+".type"));
            List<String> edges=type.equals("condition")?List.of("on_true","on_false"):List.of("next");
            for(String edge:List.of("next","on_true","on_false")) {
                if(!edges.contains(edge) && node.has(edge)) fail("conflicting_edge",path+"."+edge);
                if(edges.contains(edge)) {
                    if(!node.has(edge)) fail("missing_edge",path+"."+edge);
                    if(!node.get(edge).isNull() && !ids.contains(node.get(edge).asString())) fail("invalid_edge_target",path+"."+edge);
                }
            }
            var params=node.get("params"); var rules=spec.get("params");
            for(String key:params.propertyNames()) if(!rules.has(key)) fail("unknown_param",path+".params");
            for(var field:rules.properties()) {
                String key=field.getKey(); JsonNode rule=field.getValue(), value=params.get(key);
                String at=path+".params."+key;
                if(value==null) { if(rule.path("required").asBoolean()) fail("missing_param",at); else continue; }
                boolean typed=switch(rule.get("type").asString()) {
                    case "string" -> value.isString(); case "object" -> value.isObject(); case "number" -> value.isNumber(); default -> false;
                };
                if(!typed) fail("invalid_param_type",at);
                if(rule.has("enum")) {
                    boolean matches=false;
                    for(JsonNode allowed:rule.get("enum")) if(allowed.equals(value)) matches=true;
                    if(!matches) fail("invalid_param_enum",at);
                }
                if(rule.path("templatable").asBoolean()) templates(value,ids,at);
                else if(value.isString() && TemplateSyntax.hasDelimiters(value.asString())) fail("invalid_template",at);
            }
            literals(type,params,path+".params");
        }
    }
    private void templates(JsonNode value,Set<String> ids,String path) {
        if(value.isString()) TemplateSyntax.parse(value.asString(),ids,path);
        else if(value.isObject()) for(var field:value.properties()) {
            if(TemplateSyntax.hasDelimiters(field.getKey())) fail("invalid_template_key",path);
            templates(field.getValue(),ids,path); // Do not echo arbitrary user keys in errors.
        } else if(value.isArray()) for(JsonNode child:value) templates(child,ids,path);
    }
    private void literals(String type,JsonNode p,String path) {
        switch(type) {
            case "ai" -> schemas.validate(p.get("output_schema"),path+".output_schema");
            case "delay" -> { if(p.get("seconds").decimalValue().signum()<0) fail("invalid_param_value",path+".seconds"); }
            case "notify" -> nonblank(p.get("to"),path+".to");
            case "order_action" -> {
                nonblank(p.get("order_id"),path+".order_id");
                if(p.has("amount_usd") && (!p.get("action").asString().equals("refund") || p.get("amount_usd").decimalValue().signum()<=0))
                    fail("invalid_param_value",path+".amount_usd");
            }
            case "http_request" -> {
                String url=p.get("url").asString();
                if(!TemplateSyntax.hasDelimiters(url)) {
                    try {
                        var uri=URI.create(url);
                        if(uri.getHost()==null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                            fail("invalid_param_value",path+".url");
                    } catch(IllegalArgumentException e) { fail("invalid_param_value",path+".url"); }
                }
                if(p.has("headers")) for(var header:p.get("headers").properties()) {
                    if(!header.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || header.getKey().equalsIgnoreCase("Idempotency-Key")
                        || !header.getValue().isString() || header.getValue().asString().chars().anyMatch(c -> (c<32 && c!=9) || c==127 || c>255))
                        fail("invalid_header",path+".headers");
                }
            }
            default -> { }
        }
    }
    private void nonblank(JsonNode value,String path) { if(value.asString().isBlank()) fail("invalid_param_value",path); }
    private static void fail(String reason,String path) { throw new DefinitionException(reason,path); }
}
