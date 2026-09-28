package com.relay.engine;

import com.relay.workflow.NodeCatalog;
import com.relay.workflow.TemplateSyntax;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

public final class TemplateResolver {
    private final NodeCatalog catalog=new NodeCatalog();
    public JsonNode resolve(String type,JsonNode params,JsonNode input,Set<String> nodeIds,Function<String,JsonNode> previous) {
        var result=params.deepCopy();
        var fields=catalog.node(type).orElseThrow().get("params");
        for(String key:params.propertyNames()) {
            if(fields.get(key).path("templatable").asBoolean())
                ((tools.jackson.databind.node.ObjectNode)result).set(key,walk(params.get(key),input,nodeIds,previous));
        }
        if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>EngineJson.MAX_BYTES)throw new NodeFailure("resolved_input_too_large");
        return result;
    }
    private JsonNode walk(JsonNode value,JsonNode input,Set<String> ids,Function<String,JsonNode> previous) {
        if(value.isString())return EngineJson.JSON.getNodeFactory().stringNode(render(value.asString(),input,ids,previous));
        if(value.isObject()) {
            var object=EngineJson.JSON.createObjectNode();
            for(String key:value.propertyNames()) {
                if(TemplateSyntax.hasDelimiters(key))throw new NodeFailure("invalid_template_key");
                object.set(key,walk(value.get(key),input,ids,previous));
            }
            return object;
        }
        if(value.isArray()){var array=EngineJson.JSON.createArrayNode();value.forEach(v->array.add(walk(v,input,ids,previous)));return array;}
        return value.deepCopy();
    }
    public String render(String text,JsonNode input,Set<String> ids,Function<String,JsonNode> previous) {
        TemplateSyntax.parse(text,ids,"$.params");
        var out=new StringBuilder();int cursor=0;
        while(true) {
            int open=text.indexOf("{{",cursor);
            if(open<0){out.append(text.substring(cursor));break;}
            int close=text.indexOf("}}",open);
            out.append(text,cursor,open);
            String[] path=text.substring(open+2,close).strip().split("\\.");
            JsonNode value=path[0].equals("trigger")?input:previous.apply(path[1]);
            int start=path[0].equals("trigger")?2:3;
            if(value==null)throw new NodeFailure("missing_template_output");
            for(int i=start;i<path.length;i++) {
                if(!value.isObject() || !value.has(path[i]))throw new NodeFailure("missing_template_path");
                value=value.get(path[i]);
            }
            out.append(value.isString()?value.asString():canonical(value));
            if(out.length()>EngineJson.MAX_BYTES)throw new NodeFailure("resolved_input_too_large");
            cursor=close+2;
        }
        return out.toString();
    }
    public static String canonical(JsonNode value) {
        if(value.isNumber()) {
            var number=new BigDecimal(value.asString()).stripTrailingZeros();
            if((long)number.precision()+Math.abs((long)number.scale())>EngineJson.MAX_BYTES)throw new NodeFailure("resolved_input_too_large");
            return number.toPlainString();
        }
        if(value.isObject()) {
            var entries=new ArrayList<String>();
            for(String key:new TreeSet<>(value.propertyNames()))entries.add(EngineJson.JSON.writeValueAsString(key)+":"+canonical(value.get(key)));
            return "{"+String.join(",",entries)+"}";
        }
        if(value.isArray()){var items=new ArrayList<String>();value.forEach(v->items.add(canonical(v)));return "["+String.join(",",items)+"]";}
        return value.toString();
    }
}
