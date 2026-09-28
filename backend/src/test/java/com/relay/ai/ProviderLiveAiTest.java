package com.relay.ai;

import com.relay.engine.*;
import com.relay.workflow.OutputSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit live probe; no user payload, database, credential logging or automatic transport retry. */
class ProviderLiveAiTest {
    @Test void configuredRealProviderReturnsSchemaValidJsonWithUsage() {
        var env=new StandardEnvironment();
        assertTrue(java.util.Set.of("openai","openrouter").contains(env.getProperty("RELAY_AI_MODE","")),"Configure the live provider locally first");
        var provider=new ConfiguredAiProvider(env,new HttpTransport());
        var schema=EngineJson.read("{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"const\":true}},\"additionalProperties\":false}");
        var params=EngineJson.JSON.createObjectNode().put("prompt","Return the JSON object with ok equal to true.");params.set("output_schema",schema);
        var request=provider.prepare(params);var policy=ExecutionPolicy.from(env);boolean valid=false;
        for(int attempt=1;attempt<=2;attempt++) {
            var p=new EngineStore.Prepared(new EngineStore.Claim("live-probe","probe",1,"a",1L),attempt,request,null,policy);
            var outcome=provider.invoke(p);
            assertTrue(outcome.success(),()->"Live provider failed: "+outcome.code()+
                (outcome.code()!=null && (outcome.code().equals("ai_payment_required") || outcome.code().contains("quota") || outcome.code().contains("credit_balance") || outcome.code().contains("limit_exceeded"))
                    ?". Check API billing credits and account/project limits before retrying; application retries cannot restore access.":""));
            assertNotNull(outcome.tokensPrompt(),"Provider omitted input usage");
            assertNotNull(outcome.tokensCompletion(),"Provider omitted output usage");
            try{valid=new OutputSchemaValidator().accepts(schema,EngineJson.read(outcome.output().asString()));}catch(RuntimeException ignored){}
            if(valid)break;
            if(attempt==1)request=provider.repair(request,"invalid_ai_schema");
        }
        assertTrue(valid,"Provider output did not satisfy the schema after one repair");
    }
}
