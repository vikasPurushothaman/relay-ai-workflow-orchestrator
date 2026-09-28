package com.relay.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OutputSchemaValidatorTest {
    final JsonMapper json=new JsonMapper();
    final OutputSchemaValidator validator=new OutputSchemaValidator();
    void validate(String source) { validator.validate(json.readTree(source),"$.nodes[0].params.output_schema"); }
    @ParameterizedTest @ValueSource(strings={
        "{}", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"x\"],\"additionalProperties\":false}",
        "{\"$defs\":{\"value\":{\"type\":\"number\"}},\"$ref\":\"#/$defs/value\"}",
        "{\"$defs\":{\"value\":{\"$anchor\":\"value\",\"type\":\"number\"}},\"$ref\":\"#value\"}",
        "{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"#\"}}}",
        "{\"type\":\"string\",\"format\":\"email\",\"description\":\"{{literal schema text}}\",\"default\":\"not-email\"}",
        "{\"const\":{\"$ref\":\"https://example.test/data\"},\"examples\":[{\"$schema\":\"data\"}],\"customAnnotation\":{\"$ref\":\"literal\"}}",
        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$vocabulary\":{\"https://example.test/optional\":false}}"
    }) void validSchemas(String source) { assertThatCode(()->validate(source)).doesNotThrowAnyException(); }
    @ParameterizedTest @ValueSource(strings={
        "{\"type\":\"not-a-type\"}","{\"required\":\"x\"}","{\"maxLength\":-1}","{\"properties\":{\"x\":3}}",
        "{\"$schema\":\"http://example.test/schema\"}","{\"$ref\":\"https://example.test/schema\"}",
        "{\"$dynamicRef\":\"file:///tmp/schema\"}","{\"$ref\":\"#/$defs/missing\"}",
        "{\"$defs\":{\"unused\":{\"$ref\":\"#/missing\"}}}",
        "{\"properties\":{\"x\":{\"$ref\":\"https://example.test/schema\"}}}",
        "{\"$vocabulary\":{\"https://example.test/required\":true}}",
        "{\"$vocabulary\":{\"https://json-schema.org/draft/2020-12/vocab/format-assertion\":true}}",
        "{\"pattern\":\"[\"}","{\"$defs\":{\"unused\":{\"pattern\":\"[\"}}}"
    }) void invalidSchemas(String source) {
        assertThatThrownBy(()->validate(source)).isInstanceOf(DefinitionException.class)
            .hasMessageNotContaining("example.test").hasMessageNotContaining("file:");
    }
    @Test void resourceScopesEscapedPointersAndDynamicReferences() {
        validate("{\"$defs\":{\"a/b\":{\"type\":\"string\"}},\"$ref\":\"#/$defs/a~1b\"}");
        validate("{\"$id\":\"https://example.test/root\",\"$defs\":{\"child\":{\"$id\":\"child\",\"$defs\":{\"v\":{\"type\":\"number\"}},\"$ref\":\"#/$defs/v\"}}}");
        validate("{\"$dynamicAnchor\":\"root\",\"type\":\"object\",\"properties\":{\"child\":{\"$dynamicRef\":\"#root\"}}}");
    }
    @Test void referencedAnnotationMustObeySchemaPolicy() {
        assertThatThrownBy(()->validate("{\"$ref\":\"#/custom\",\"custom\":{\"$ref\":\"https://json-schema.org/draft/2020-12/schema\"}}"))
            .isInstanceOfSatisfying(DefinitionException.class,e->assertThat(e.reason()).isEqualTo("unsupported_schema_reference"));
        validate("{\"$ref\":\"#/custom\",\"custom\":{\"type\":\"string\"}}");
    }
    @Test void externalResourcesNeverFetched() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes());exchange.close();});
        server.start();
        try {
            String address="http://127.0.0.1:"+server.getAddress().getPort()+"/schema";
            for(String key:java.util.List.of("$ref","$schema","$dynamicRef")) {
                String source="{\""+key+"\":\""+address+"\"}";
                assertThatThrownBy(()->validate(source)).isInstanceOf(DefinitionException.class);
            }
            assertThat(calls.get()).isZero();
        } finally {server.stop(0);}
    }
}
