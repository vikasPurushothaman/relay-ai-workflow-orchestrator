package com.relay.api;

import com.relay.engine.EngineJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static org.assertj.core.api.Assertions.*;

class RunQueryTest {
    static MultiValueMap<String,String> params(String... pairs) {
        var map=new LinkedMultiValueMap<String,String>();
        for(int i=0;i<pairs.length;i+=2)map.add(pairs[i],pairs[i+1]);
        return map;
    }
    static String b64(String raw){return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));}
    static void rejected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiFailure.class,f->assertThat(f.reason()).isEqualTo(ApiFailure.Reason.INVALID_INPUT));
    }

    @Test void cursorRoundTripsIncludingUnicodeAndMicroseconds() {
        for(String id:new String[]{"run_1","😀".repeat(128),"a:b"}) {
            var c=new RunQueryService.Cursor(Instant.parse("2026-09-28T10:15:30.123456Z"),id);
            assertThat(RunQueryService.decode(RunQueryService.encode(c))).isEqualTo(c);
        }
        var epoch=new RunQueryService.Cursor(Instant.EPOCH,"r");
        assertThat(RunQueryService.decode(RunQueryService.encode(epoch))).isEqualTo(epoch);
    }
    @Test void malformedCursorsAreRejected() {
        String valid=RunQueryService.encode(new RunQueryService.Cursor(Instant.parse("2026-09-28T00:00:00Z"),"run_1"));
        for(String bad:new String[]{"!!!",valid+"=","a",b64("run_1"),b64(":run_1"),b64("-5:run_1"),b64("12x:run_1"),b64("1:"),
                b64("1234567890123456789:run_1"),b64("1:"+"x".repeat(129)),"x".repeat(1025),
                b64("300000000000000000:run_1"), // year ~11476, beyond MySQL DATETIME
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{'1',':',(byte)0xff})})
            rejected(()->RunQueryService.decode(bad));
    }
    @Test void listDefaultsAndBoundaries() {
        var q=RunQueryService.listQuery(params());
        assertThat(q.limit()).isEqualTo(25);assertThat(q.workflowId()).isNull();assertThat(q.status()).isNull();assertThat(q.cursor()).isNull();
        assertThat(RunQueryService.listQuery(params("limit","1")).limit()).isEqualTo(1);
        assertThat(RunQueryService.listQuery(params("limit","100")).limit()).isEqualTo(100);
        assertThat(RunQueryService.listQuery(params("workflow_id","","status","","cursor","","limit","")).limit()).isEqualTo(25);
        for(String s:RunQueryService.STATUSES)assertThat(RunQueryService.listQuery(params("status",s)).status()).isEqualTo(s);
        assertThat(RunQueryService.listQuery(params("workflow_id","wf_x")).workflowId()).isEqualTo("wf_x");
    }
    @Test void runSummarySerializesFrozenCapIncludingNull() {
        var withCap=new RunQueryService.RunSummary("r","w","queued","manual","n",0,12L,Instant.EPOCH,null,null);
        var withoutCap=new RunQueryService.RunSummary("r","w","queued","manual","n",0,null,Instant.EPOCH,null,null);
        assertThat(EngineJson.JSON.valueToTree(withCap).get("max_steps").asLong()).isEqualTo(12);
        assertThat(EngineJson.JSON.valueToTree(withoutCap).get("max_steps").isNull()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"0","101","-1","abc","1.5","9999999999"," 5"})
    void invalidLimitsAreRejected(String limit){rejected(()->RunQueryService.listQuery(params("limit",limit)));}
    @Test void invalidListFiltersAreRejected() {
        rejected(()->RunQueryService.listQuery(params("status","pending")));
        rejected(()->RunQueryService.listQuery(params("status","SUCCEEDED")));
        rejected(()->RunQueryService.listQuery(params("workflow_id","x".repeat(129))));
        rejected(()->RunQueryService.listQuery(params("status","queued","status","failed")));
        rejected(()->RunQueryService.listQuery(params("workflowId","wf")));
    }
    @Test void stepPagingBoundaries() {
        var q=RunQueryService.stepQuery(params());
        assertThat(q.after()).isZero();assertThat(q.limit()).isEqualTo(500);
        assertThat(RunQueryService.stepQuery(params("steps_after","7","steps_limit","1"))).isEqualTo(new RunQueryService.StepQuery(7,1));
        assertThat(RunQueryService.stepQuery(params("steps_limit","500")).limit()).isEqualTo(500);
        for(String bad:new String[]{"-1","x","1e3","1234567890123456789"})rejected(()->RunQueryService.stepQuery(params("steps_after",bad)));
        for(String bad:new String[]{"0","501","x"})rejected(()->RunQueryService.stepQuery(params("steps_limit",bad)));
        rejected(()->RunQueryService.stepQuery(params("limit","5")));
    }
}
