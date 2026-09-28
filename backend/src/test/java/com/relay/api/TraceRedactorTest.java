package com.relay.api;

import com.relay.engine.EngineJson;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TraceRedactorTest {
    final TraceRedactor r=new TraceRedactor("hook-secret-value");
    String red(String json){return r.redact(EngineJson.read(json)).toString();}

    @Test void sensitiveKeyNamesAreRedactedAtAnyDepthAndFormat() {
        for(String key:new String[]{"Authorization","authorization","X-Api-Key","api_key","apiKey","access_token","refreshToken","password","Passwd","client_secret",
                "Cookie","Set-Cookie","credentials","private_key","X-Hub-Signature","X-Relay-Secret"})
            assertThat(TraceRedactor.sensitive(key)).as(key).isTrue();
        for(String key:new String[]{"idempotency_key","order_id","message","url","amount_usd","status","key","body","headers","monkey"})
            assertThat(TraceRedactor.sensitive(key)).as(key).isFalse();
        String out=red("{\"headers\":{\"Authorization\":\"Bearer abc\",\"X-Trace\":\"ok\"},\"list\":[{\"password\":{\"nested\":1}},\"plain\"],\"n\":5,\"b\":true,\"z\":null}");
        assertThat(out).isEqualTo("{\"headers\":{\"Authorization\":\"[REDACTED]\",\"X-Trace\":\"ok\"},\"list\":[{\"password\":\"[REDACTED]\"},\"plain\"],\"n\":5,\"b\":true,\"z\":null}");
    }
    @Test void inputIsNotMutated() {
        var node=EngineJson.read("{\"token\":\"t\"}");r.redact(node);
        assertThat(node.toString()).isEqualTo("{\"token\":\"t\"}");
        assertThat(r.redact(null)).isNull();
    }
    @Test void webhookSecretValueIsReplacedInsideStrings() {
        assertThat(red("{\"note\":\"prefix hook-secret-value suffix\",\"arr\":[\"hook-secret-value\"]}"))
            .isEqualTo("{\"note\":\"prefix [REDACTED] suffix\",\"arr\":[\"[REDACTED]\"]}");
        // Short secrets are skipped for value matching (would destroy the trace); key rules still apply.
        assertThat(new TraceRedactor("abc").text("abc abc")).isEqualTo("abc abc");
        assertThat(new TraceRedactor(null).text("x")).isEqualTo("x");
        assertThat(new TraceRedactor("12345678").text("a12345678b")).isEqualTo("a[REDACTED]b");
    }
    @Test void urlUserInfoAndSensitiveQueryValuesAreRedacted() {
        assertThat(TraceRedactor.url("https://user:pw@example.test:8443/p?api_key=K&ok=1&access%5Ftoken=T#frag"))
            .isEqualTo("https://[REDACTED]@example.test:8443/p?api_key=[REDACTED]&ok=1&access%5Ftoken=[REDACTED]#frag");
        for(String same:new String[]{"http://example.test/p?ok=1","https://example.test","not a url https://x?token=1","ftp://u:p@h/","http://bad host/?token=1","http://h/?%zz=1&flag"})
            assertThat(TraceRedactor.url(same)).isEqualTo(same);
        assertThat(red("{\"url\":\"http://127.0.0.1:9/x?Token=abc\"}")).isEqualTo("{\"url\":\"http://127.0.0.1:9/x?Token=[REDACTED]\"}");
    }
}
