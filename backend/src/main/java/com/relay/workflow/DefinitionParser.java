package com.relay.workflow;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class DefinitionParser {
    public static final int MAX_BYTES=1024*1024;
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BYTES).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public WorkflowDefinition parse(String source) {
        if(source==null) throw error("missing_json", "$");
        if(source.length()>MAX_BYTES || source.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw error("size_limit", "$");
        JsonNode root;
        try { root=JSON.readTree(source); }
        catch (RuntimeException e) { throw error("invalid_json", "$"); }
        fields(root, "$", Set.of("id","name","description","trigger","entry","limits","nodes"));
        text(root.get("id"), "$.id",128,true);
        text(root.get("name"), "$.name",200,true);
        if(root.has("description")) text(root.get("description"),"$.description",4000,false);
        identifier(root.get("entry"),"$.entry");
        JsonNode trigger=root.get("trigger");
        fields(trigger,"$.trigger",Set.of("type","secret"));
        String type=text(trigger.get("type"),"$.trigger.type",128,true);
        if(!Set.of("manual","webhook").contains(type)) throw error("unsupported_trigger","$.trigger.type");
        if(type.equals("manual") && trigger.has("secret")) throw error("unknown_field","$.trigger");
        if(type.equals("webhook")) {
            String secret=text(trigger.get("secret"),"$.trigger.secret",1024,true);
            if(secret.getBytes(StandardCharsets.UTF_8).length>1024 || secret.contains("{{") || secret.contains("}}"))
                throw error("invalid_secret","$.trigger.secret");
        }
        JsonNode limits=root.get("limits");
        fields(limits,"$.limits",Set.of("max_steps","timeout_seconds","max_ai_tokens"));
        integer(limits.get("max_steps"),"$.limits.max_steps",true);
        for(String key:List.of("timeout_seconds","max_ai_tokens"))
            if(limits.has(key)) integer(limits.get(key),"$.limits."+key,false);
        JsonNode nodes=root.get("nodes");
        if(nodes==null || !nodes.isArray() || nodes.isEmpty()) throw error("expected_nonempty_array","$.nodes");
        Set<String> ids=new HashSet<>();
        int i=0;
        for(JsonNode node:nodes) {
            String path="$.nodes["+(i++)+"]";
            fields(node,path,Set.of("id","type","params","next","on_true","on_false"));
            String id=identifier(node.get("id"),path+".id");
            if(!ids.add(id)) throw error("duplicate_node_id",path+".id");
            text(node.get("type"),path+".type",MAX_BYTES,true);
            if(node.get("params")==null || !node.get("params").isObject()) throw error("expected_object",path+".params");
            for(String edge:List.of("next","on_true","on_false"))
                if(node.has(edge) && !node.get(edge).isNull()) identifier(node.get(edge),path+"."+edge);
        }
        return new WorkflowDefinition(root);
    }
    private static void fields(JsonNode node,String path,Set<String> allowed) {
        if(node==null || !node.isObject()) throw error("expected_object",path);
        for(String name:node.propertyNames()) if(!allowed.contains(name)) throw error("unknown_field",path);
    }
    private static String text(JsonNode node,String path,int max,boolean nonblank) {
        if(node==null || !node.isString()) throw error("expected_string",path);
        String value=node.asString();
        if((nonblank && value.isBlank()) || value.codePointCount(0,value.length())>max) throw error("invalid_string",path);
        return value;
    }
    private static String identifier(JsonNode node,String path) {
        String value=text(node,path,128,true);
        if(!value.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}")) throw error("invalid_node_id",path);
        return value;
    }
    private static void integer(JsonNode node,String path,boolean positive) {
        if(node==null || !node.isIntegralNumber() || node.bigIntegerValue().signum()<(positive?1:0)
            || (positive && node.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE))>0))
            throw error("invalid_limit",path);
    }
    private static DefinitionException error(String reason,String path) { return new DefinitionException(reason,path); }
}
