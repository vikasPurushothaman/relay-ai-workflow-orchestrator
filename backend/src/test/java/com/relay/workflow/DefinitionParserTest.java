package com.relay.workflow;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class DefinitionParserTest {
    final DefinitionParser parser=new DefinitionParser();
    final JsonMapper json=new JsonMapper();
    static final String VALID="""
        {"id":"wf","name":"Example","trigger":{"type":"manual"},"entry":"a",
         "limits":{"max_steps":1},"nodes":[{"id":"a","type":"delay","params":{},"next":null}]}
        """;
    @Test void unchangedSeedsAndCatalog() throws Exception {
        var base=Path.of("../docs/source-review/pack/data");
        var seeds=json.readTree(Files.readString(base.resolve("seed_workflows.json"))).get("workflows");
        assertThat(seeds.size()).isEqualTo(4);
        for(var seed:seeds) assertThat(parser.parse(seed.toString()).json()).isEqualTo(seed);
        var catalog=new NodeCatalog();
        assertThat(catalog.source()).isEqualTo(json.readTree(Files.readString(base.resolve("node_catalog.json"))));
        assertThat(Files.readAllBytes(Path.of("src/main/resources/catalog/node_catalog.json")))
            .isEqualTo(Files.readAllBytes(base.resolve("node_catalog.json")));
        assertThat(catalog.types()).containsExactly("http_request","condition","delay","notify","ai","approval","order_action");
        assertThat(catalog.supportedTriggers()).containsExactlyInAnyOrder("manual","webhook");
        assertThat(catalog.node("schedule")).isEmpty();
        for(String type:catalog.types()) assertThat(catalog.node(type).orElseThrow()).isEqualTo(
            java.util.stream.StreamSupport.stream(catalog.source().get("nodes").spliterator(),false)
                .filter(n->n.get("type").asString().equals(type)).findFirst().orElseThrow());
        ((ObjectNode)catalog.node("delay").orElseThrow()).put("side_effect",true);
        assertThat(catalog.node("delay").orElseThrow().get("side_effect").asBoolean()).isFalse();
    }
    @Test void defensiveDraftAndExactValues() {
        var parsed=parser.parse(VALID.replace("\"params\":{}","\"params\":{\"x\":0.12345678901234567890123456789,\"data\":null}")
            .replace("\"delay\"","\"future_type\"").replace("\"entry\":\"a\"","\"entry\":\"missing\""));
        assertThat(parsed.node("a").orElseThrow().get("params").get("x").decimalValue())
            .isEqualByComparingTo("0.12345678901234567890123456789");
        assertThat(parsed.nodeIds()).containsExactly("a");
        assertThat(parsed.node("missing")).isEmpty();
        ((ObjectNode)parsed.json()).put("id","changed");
        ((ObjectNode)parsed.node("a").orElseThrow()).put("type","changed");
        assertThat(parsed.id()).isEqualTo("wf");
        assertThat(parsed.node("a").orElseThrow().get("type").asString()).isEqualTo("future_type");
        assertThat(parsed.toString()).doesNotContain("wf","future_type");
        assertThat(parsed.node("a").orElseThrow().get("next").isNull()).isTrue();
        assertThat(parser.parse(VALID.replace(",\"next\":null","")).node("a").orElseThrow().has("next")).isFalse();
        assertThat(parser.parse(VALID.replace("\"next\":null","\"next\":\"a\"")).entry()).isEqualTo("a");
    }
    @ParameterizedTest @ValueSource(strings={"", "null", "[]", "true", "{}", "{", "{} {}"})
    void rejectsInvalidRoots(String source) { assertThatThrownBy(()->parser.parse(source)).isInstanceOf(DefinitionException.class); }
    @Test void structuralFailures() {
        for(String invalid:List.of(
            VALID.replace("\"id\":\"wf\"","\"id\":null"),
            VALID.replace("\"name\":\"Example\"","\"name\":\" \""),
            VALID.replace("\"manual\"","\"schedule\""),
            VALID.replace("\"manual\"","\"webhook\""),
            VALID.replace("\"manual\"","\"manual\",\"secret\":\"hidden\""),
            VALID.replace("\"max_steps\":1","\"max_steps\":0"),
            VALID.replace("\"max_steps\":1","\"max_steps\":2147483648"),
            VALID.replace("\"max_steps\":1","\"max_steps\":1.0"),
            VALID.replace("\"max_steps\":1","\"max_steps\":true"),
            VALID.replace("\"max_steps\":1","\"max_steps\":1,\"timeout_seconds\":-1"),
            VALID.replace("\"params\":{}","\"params\":[]"),
            VALID.replace("\"id\":\"a\"","\"id\":\"a.b\""),
            VALID.replace("\"next\":null","\"next\":3"),
            VALID.replace("\"params\":{}","\"params\":{\"password\":1,\"password\":2}"),
            VALID.replace("\"id\":\"wf\"","\"id\":\"wf\",\"id\":\"other\""),
            VALID.replace("\"id\":\"wf\"","\"id\":\"wf\",\"secret-sentinel\":true"),
            VALID.replace("}]}","},{\"id\":\"a\",\"type\":\"delay\",\"params\":{}}]}"),
            VALID.replace("[{\"id\":\"a\",\"type\":\"delay\",\"params\":{},\"next\":null}]","[]"))) {
            assertThatThrownBy(()->parser.parse(invalid)).isInstanceOf(DefinitionException.class)
                .hasMessageNotContaining("hidden").hasMessageNotContaining("password").hasMessageNotContaining("secret-sentinel");
        }
        assertThatThrownBy(()->parser.parse(null)).isInstanceOf(DefinitionException.class);
    }
    @Test void requiredFieldsAndUnknownStructure() {
        for(String field:List.of("id","name","trigger","entry","limits","nodes")) {
            ObjectNode root=(ObjectNode)json.readTree(VALID);
            root.remove(field);
            assertThatThrownBy(()->parser.parse(root.toString())).isInstanceOf(DefinitionException.class);
        }
        for(String field:List.of("id","type","params")) {
            ObjectNode root=(ObjectNode)json.readTree(VALID);
            ((ObjectNode)root.get("nodes").get(0)).remove(field);
            assertThatThrownBy(()->parser.parse(root.toString())).isInstanceOf(DefinitionException.class);
        }
        for(String pointer:List.of("/trigger","/limits","/nodes/0")) {
            ObjectNode root=(ObjectNode)json.readTree(VALID);
            ((ObjectNode)root.at(pointer)).put("private-sentinel",true);
            assertThatThrownBy(()->parser.parse(root.toString())).isInstanceOf(DefinitionException.class)
                .hasMessageNotContaining("private-sentinel");
        }
        for(String secret:List.of("", "{{hidden}}", "hidden}}")) {
            ObjectNode root=(ObjectNode)json.readTree(VALID);
            ((ObjectNode)root.get("trigger")).put("type","webhook").put("secret",secret);
            assertThatThrownBy(()->parser.parse(root.toString())).isInstanceOf(DefinitionException.class)
                .hasMessageNotContaining("hidden");
        }
        ObjectNode root=(ObjectNode)json.readTree(VALID);
        root.put("description","x".repeat(4000));
        parser.parse(root.toString());
        root.put("description","x".repeat(4001));
        assertThatThrownBy(()->parser.parse(root.toString())).isInstanceOf(DefinitionException.class);
        // UTF-8 bytes, rather than Java character count, govern the input budget.
        root.put("description","é".repeat(DefinitionParser.MAX_BYTES/2));
        assertThatThrownBy(()->parser.parse(root.toString())).isInstanceOf(DefinitionException.class)
            .hasMessageContaining("size_limit");
    }
    @Test void boundaries() {
        String unicode="😀".repeat(128);
        assertThat(parser.parse(VALID.replace("\"wf\"","\""+unicode+"\"")).id()).isEqualTo(unicode);
        assertThatThrownBy(()->parser.parse(VALID.replace("\"wf\"","\""+unicode+"a\""))).isInstanceOf(DefinitionException.class);
        parser.parse(VALID.replace("Example","x".repeat(200)));
        assertThatThrownBy(()->parser.parse(VALID.replace("Example","x".repeat(201)))).isInstanceOf(DefinitionException.class);
        parser.parse(VALID.replace("\"max_steps\":1","\"max_steps\":2147483647,\"timeout_seconds\":0,\"max_ai_tokens\":0"));
        String padded=VALID+" ".repeat(DefinitionParser.MAX_BYTES-VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        parser.parse(padded);
        assertThatThrownBy(()->parser.parse(padded+" ")).isInstanceOf(DefinitionException.class);
        String deep=VALID.replace("\"params\":{}","\"params\":{\"nested\":"+"[".repeat(60)+"0"+"]".repeat(60)+"}");
        parser.parse(deep);
        assertThatThrownBy(()->parser.parse(deep.replace("0]","[0]]"))).isInstanceOf(DefinitionException.class);
        parser.parse(VALID.replace("\"manual\"","\"webhook\",\"secret\":\""+"é".repeat(512)+"\""));
        assertThatThrownBy(()->parser.parse(VALID.replace("\"manual\"","\"webhook\",\"secret\":\""+"é".repeat(513)+"\""))).isInstanceOf(DefinitionException.class);
    }
}
