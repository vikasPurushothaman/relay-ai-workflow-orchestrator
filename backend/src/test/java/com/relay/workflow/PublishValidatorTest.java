package com.relay.workflow;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class PublishValidatorTest {
    final JsonMapper json=new JsonMapper();
    final DefinitionParser parser=new DefinitionParser();
    final NodeCatalog catalog=new NodeCatalog();
    final PublishValidator validator=new PublishValidator(catalog,new OutputSchemaValidator());
    ObjectNode graph(String type,String params) {
        return (ObjectNode)json.readTree("""
            {"id":"wf","name":"Test","trigger":{"type":"manual"},"entry":"a","limits":{"max_steps":1},
             "nodes":[{"id":"a","type":"%s","params":%s,%s}]}
            """.formatted(type,params,type.equals("condition")?"\"on_true\":null,\"on_false\":null":"\"next\":null"));
    }
    ObjectNode node(ObjectNode root) { return (ObjectNode)root.get("nodes").get(0); }
    void valid(JsonNode root) { var draft=parser.parse(root.toString()); var before=draft.json(); validator.validate(draft); assertThat(draft.json()).isEqualTo(before); }
    void invalid(JsonNode root,String reason) {
        assertThatThrownBy(()->validator.validate(parser.parse(root.toString())))
            .isInstanceOfSatisfying(DefinitionException.class,e->assertThat(e.reason()).isEqualTo(reason));
    }
    Map<String,String> examples() { return Map.of(
        "delay","{\"seconds\":0.1}","condition","{\"left\":\"1\",\"op\":\"equals\",\"right\":\"1\"}",
        "http_request","{\"method\":\"GET\",\"url\":\"http://localhost:9210/health\",\"headers\":{},\"body\":{}}",
        "notify","{\"channel\":\"email\",\"to\":\"x\",\"subject\":\"\",\"message\":\"\"}",
        "ai","{\"prompt\":\"\",\"output_schema\":{}}", "approval","{\"message\":\"\"}",
        "order_action","{\"action\":\"refund\",\"order_id\":\"o\",\"amount_usd\":1}"); }
    @Test void seedsAndEveryCatalogParameter() throws Exception {
        for(JsonNode seed:json.readTree(Files.readString(Path.of("../docs/source-review/pack/data/seed_workflows.json"))).get("workflows")) valid(seed);
        for(var example:examples().entrySet()) {
            valid(graph(example.getKey(),example.getValue()));
            var rules=catalog.node(example.getKey()).orElseThrow().get("params");
            for(var field:rules.properties()) {
                String name=field.getKey();
                var missing=graph(example.getKey(),example.getValue());
                ((ObjectNode)node(missing).get("params")).remove(name);
                if(field.getValue().path("required").asBoolean()) invalid(missing,"missing_param"); else valid(missing);
                for(JsonNode bad:List.of(json.readTree("null"),json.readTree("true"),json.readTree("[]"))) {
                    var root=graph(example.getKey(),example.getValue());
                    ((ObjectNode)node(root).get("params")).set(name,bad);
                    invalid(root,"invalid_param_type");
                }
                if(field.getValue().has("enum")) {
                    for(JsonNode allowed:field.getValue().get("enum")) {
                        var candidate=graph(example.getKey(),example.getValue());
                        var cp=(ObjectNode)node(candidate).get("params");
                        cp.set(name,allowed);
                        if(example.getKey().equals("order_action") && allowed.asString().equals("replacement")) cp.remove("amount_usd");
                        valid(candidate);
                    }
                    var root=graph(example.getKey(),example.getValue());
                    ((ObjectNode)node(root).get("params")).put(name,"not-an-enum"); invalid(root,"invalid_param_enum");
                }
            }
            var root=graph(example.getKey(),example.getValue());
            ((ObjectNode)node(root).get("params")).put("private-sentinel",1); invalid(root,"unknown_param");
        }
    }
    @Test void graphRulesAllowLoopsAndValidateUnreachableNodes() {
        var root=graph("delay","{\"seconds\":0}");
        node(root).put("next","a"); valid(root);
        root.put("entry","missing"); invalid(root,"invalid_entry"); root.put("entry","a");
        node(root).put("type","teleport"); invalid(root,"invalid_node_type"); node(root).put("type","delay");
        node(root).put("next","missing"); invalid(root,"invalid_edge_target");
        node(root).remove("next"); invalid(root,"missing_edge"); node(root).putNull("next");
        node(root).putNull("on_true"); invalid(root,"conflicting_edge"); node(root).remove("on_true");
        var unreachable=node(root).deepCopy(); unreachable.put("id","b").put("type","unknown");
        ((tools.jackson.databind.node.ArrayNode)root.get("nodes")).add(unreachable); invalid(root,"invalid_node_type");
        unreachable.put("type","delay").put("next","a"); valid(root);
        var branch=graph("condition",examples().get("condition"));
        node(branch).put("on_true","a").put("on_false","a"); valid(branch);
        node(branch).remove("on_false"); invalid(branch,"missing_edge");
        node(branch).putNull("on_false").putNull("next"); invalid(branch,"conflicting_edge");
    }
    @ParameterizedTest @ValueSource(strings={"{{trigger.body}}","x {{ trigger.body.a-b.0 }} y", "{{nodes.a.output}}", "{{nodes.a.output.foo}} {{trigger.body}}", "plain { text }"})
    void templatePositive(String text) { assertThatCode(()->TemplateSyntax.parse(text,Set.of("a"),"$.params")).doesNotThrowAnyException(); }
    @ParameterizedTest @ValueSource(strings={"{{", "}}", "{{trigger.body}", "{{{trigger.body}}}","{{trigger.body}}}","{{trigger.body..x}}", "{{trigger.body[0]}}", "{{trigger.body.x | run}}", "{{nodes.a.output()}}", "{{unknown}}", "{{nodes.missing.output}}"})
    void templateNegative(String text) { assertThatThrownBy(()->TemplateSyntax.parse(text,Set.of("a"),"$.params")).isInstanceOf(DefinitionException.class); }
    @Test void recursiveTemplatesAndLiteralRules() {
        var root=graph("http_request",examples().get("http_request"));
        var params=(ObjectNode)node(root).get("params");
        params.set("body",json.readTree("{\"nested\":[null,3,true,{\"x\":\"{{nodes.a.output}}\"}]}")); valid(root);
        params.set("body",json.readTree("{\"{{secret-sentinel}}\":0}")); invalid(root,"invalid_template_key");
        params.set("body",json.readTree("{}"));
        for(String headers:List.of("{\"IdEmPoTeNcY-Key\":\"x\"}","{\"bad name\":\"x\"}","{\"X-Test\":3}","{\"X-Test\":\"x\\r\\ny\"}")) {
            params.set("headers",json.readTree(headers)); invalid(root,"invalid_header");
        }
        for(String value:List.of("bad"+(char)0,"bad"+(char)127,"bad😀")) {
            var headers=json.createObjectNode().put("X-Test",value);
            params.set("headers",headers); invalid(root,"invalid_header");
        }
        params.set("headers",json.createObjectNode().put("X-Test","valid\tvalue")); valid(root);
        params.set("headers",json.readTree("{}"));
        for(String url:List.of("", "relative/path", "file:///private/sentinel", "http://")) {params.put("url",url);invalid(root,"invalid_param_value");}
        params.put("url","{{trigger.body.url}}");valid(root);
        invalid(graph("delay","{\"seconds\":-0.01}"),"invalid_param_value");
        valid(graph("delay","{\"seconds\":0}"));
        invalid(graph("notify",examples().get("notify").replace("\"to\":\"x\"","\"to\":\" \"")),"invalid_param_value");
        invalid(graph("order_action","{\"action\":\"replacement\",\"order_id\":\"x\",\"amount_usd\":1}"),"invalid_param_value");
        invalid(graph("order_action","{\"action\":\"refund\",\"order_id\":\"x\",\"amount_usd\":0}"),"invalid_param_value");
        // No static approval dominance requirement: the runtime gate is a later invariant.
        valid(graph("order_action","{\"action\":\"refund\",\"order_id\":\"{{trigger.body.order}}\"}"));
    }
}
