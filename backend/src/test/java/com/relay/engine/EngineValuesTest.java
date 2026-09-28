package com.relay.engine;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class EngineValuesTest {
    @Test void policyIsValidatedFrozenAndBackoffSaturates() {
        var p=ExecutionPolicy.from(new MockEnvironment());
        assertThat(ExecutionPolicy.parse(p.json().toString())).isEqualTo(p);
        assertThat(p.backoff(1)).isEqualTo(1000);assertThat(p.backoff(2)).isEqualTo(2000);assertThat(p.backoff(100)).isEqualTo(30000);
        assertThatThrownBy(()->ExecutionPolicy.from(new MockEnvironment().withProperty("RELAY_JOB_LEASE_MS","50000"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExecutionPolicy.from(new MockEnvironment().withProperty("RELAY_RETRY_MAX_ATTEMPTS","101"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExecutionPolicy.parse("{\"policy_version\":2}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ExecutionPolicy(Integer.MAX_VALUE,Integer.MAX_VALUE,5000,Integer.MAX_VALUE,30000,3,1,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->TemplateResolver.canonical(EngineJson.read("1e9999999"))).isInstanceOf(NodeFailure.class);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","1.5","2147483648","abc"})
    void invalidMilliseconds(String value){assertThatThrownBy(()->ExecutionPolicy.from(new MockEnvironment().withProperty("RELAY_HTTP_TIMEOUT_MS",value))).isInstanceOf(IllegalArgumentException.class);}
    @Test void templateValuesAreTextCanonicalAndNeverEvaluatedTwice() {
        var resolver=new TemplateResolver();var input=EngineJson.read("{\"b\":2.00,\"a\":null,\"msg\":\"{{nodes.evil.output}}\",\"arr\":[1],\"zero\":-0.0}");
        assertThat(resolver.render("{{trigger.body}}",input,Set.of(),id->null)).isEqualTo("{\"a\":null,\"arr\":[1],\"b\":2,\"msg\":\"{{nodes.evil.output}}\",\"zero\":0}");
        assertThat(resolver.render("x={{trigger.body.a}}",input,Set.of(),id->null)).isEqualTo("x=null");
        assertThat(resolver.render("{{trigger.body.msg}}",input,Set.of(),id->null)).isEqualTo("{{nodes.evil.output}}");
        assertThat(resolver.render("{{nodes.a.output.x}}",input,Set.of("a"),id->EngineJson.read("{\"x\":true}"))).isEqualTo("true");
        for(String path:new String[]{"trigger.body.missing","trigger.body.a.x","trigger.body.arr.0","nodes.a.output"})
            assertThatThrownBy(()->resolver.render("{{"+path+"}}",input,Set.of("a"),id->null)).isInstanceOf(NodeFailure.class);
        var resolved=resolver.resolve("http_request",EngineJson.read("{\"url\":\"http://localhost\",\"method\":\"POST\",\"body\":{\"v\":\"{{trigger.body.b}}\",\"n\":2}}"),input,Set.of(),id->null);
        assertThat(resolved.get("body").get("v").asString()).isEqualTo("2");assertThat(resolved.get("body").get("n").isNumber()).isTrue();
    }
    @Test void exactConditionsAndDelayBoundaries() {
        var p=EngineJson.JSON.createObjectNode().put("left","100").put("right","100.0").put("op","equals");
        assertThat(DeterministicNodes.condition(p)).isFalse();p.put("op","not_equals");assertThat(DeterministicNodes.condition(p)).isTrue();
        p.put("op","greater_than");assertThat(DeterministicNodes.condition(p)).isFalse();p.put("left"," 1.01e2 ");assertThat(DeterministicNodes.condition(p)).isTrue();
        p.put("op","less_than").put("left","99");assertThat(DeterministicNodes.condition(p)).isTrue();
        p.put("left","NaN");assertThatThrownBy(()->DeterministicNodes.condition(p)).isInstanceOf(NodeFailure.class);
        p.put("op","contains").put("right","");assertThat(DeterministicNodes.condition(p)).isTrue();
        Instant now=Instant.parse("2026-01-01T00:00:00Z");
        assertThat(DeterministicNodes.delay(now,EngineJson.read("0.0001"))).isEqualTo(now.plusMillis(1));
        assertThat(DeterministicNodes.delay(now,EngineJson.read("0"))).isEqualTo(now);
        assertThatThrownBy(()->DeterministicNodes.delay(now,EngineJson.read("-0.0001"))).isInstanceOf(NodeFailure.class);
        assertThatThrownBy(()->DeterministicNodes.delay(now,EngineJson.read("1e30"))).isInstanceOf(NodeFailure.class);
    }
    @Test void destinationsHeadersAndExactAdapterContracts() {
        var policy=new DestinationPolicy("http://localhost:9210");
        for(String bad:new String[]{"http://localhost:9211/x","http://localhost:9210.evil/x","http://user:pw@localhost:9210/x","file:///etc/passwd","http://localhost:9210/#x"})
            assertThatThrownBy(()->policy.check(bad)).isInstanceOf(NodeFailure.class);
        var notify=DeterministicNodes.request("notify",EngineJson.read("{\"channel\":\"chat\",\"to\":\"#ops\",\"message\":\"hi\",\"subject\":\"ignored\"}"),"http://localhost:9210",policy);
        assertThat(notify.get("url").asString()).endsWith("/chat/message");assertThat(notify.get("body").asString()).isEqualTo("{\"channel\":\"#ops\",\"message\":\"hi\"}");
        var order=DeterministicNodes.request("order_action",EngineJson.read("{\"action\":\"refund\",\"order_id\":\"a/b ?\",\"amount_usd\":1.50}"),"http://localhost:9210",policy);
        assertThat(order.get("url").asString()).endsWith("/orders/a%2Fb%20%3F/refund");assertThat(order.get("body").asString()).isEqualTo("{\"amount_usd\":1.5}");
        for(String header:new String[]{"Idempotency-Key","Host","authorization","Cookie"}) {
            var p=EngineJson.JSON.createObjectNode().put("method","POST").put("url","http://localhost:9210/x");p.set("headers",EngineJson.JSON.createObjectNode().put(header,"value"));
            assertThatThrownBy(()->DeterministicNodes.request("http_request",p,"http://localhost:9210",policy)).isInstanceOf(NodeFailure.class);
        }
        var at=Instant.parse("2026-01-01T00:00:00Z");
        assertThat(new HttpTransport.Outcome(null,"http_429",true,"3").retryAt(at)).isEqualTo(at.plusSeconds(3));
        assertThat(new HttpTransport.Outcome(null,"http_503",true,"Thu, 1 Jan 2026 00:00:05 GMT").retryAt(at)).isEqualTo(at.plusSeconds(5));
        assertThat(new HttpTransport.Outcome(null,"http_503",true,"bad").retryAt(at)).isNull();
    }
}
