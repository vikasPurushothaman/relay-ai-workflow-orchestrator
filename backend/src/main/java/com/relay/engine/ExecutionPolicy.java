package com.relay.engine;

import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/** Frozen per-run limits. Millisecond arithmetic is widened before validation. */
public record ExecutionPolicy(int leaseMs,int renewMs,int dbMs,int httpMs,int aiMs,int maxAttempts,int baseMs,int maxMs) {
    public ExecutionPolicy {
        if(leaseMs<=0 || renewMs<=0 || dbMs<=0 || httpMs<=0 || aiMs<=0 || maxAttempts<1 || maxAttempts>100
                || baseMs<=0 || maxMs<baseMs || (long)leaseMs<=(long)renewMs+Math.max(httpMs,aiMs)+2L*dbMs)
            throw new IllegalArgumentException("Invalid execution policy");
    }
    public static ExecutionPolicy from(Environment env) {
        return new ExecutionPolicy(value(env,"RELAY_JOB_LEASE_MS",60000),value(env,"RELAY_JOB_RENEW_MS",10000),
            value(env,"RELAY_DB_TX_TIMEOUT_MS",5000),value(env,"RELAY_HTTP_TIMEOUT_MS",10000),value(env,"RELAY_AI_TIMEOUT_MS",30000),
            value(env,"RELAY_RETRY_MAX_ATTEMPTS",3),value(env,"RELAY_RETRY_BASE_MS",1000),value(env,"RELAY_RETRY_MAX_MS",30000));
    }
    public static int value(Environment e,String key,int fallback) {
        String text=e.getProperty(key,Integer.toString(fallback));
        try {if(!text.matches("[0-9]+"))throw new NumberFormatException();int n=Integer.parseInt(text);if(n<1)throw new NumberFormatException();return n;}
        catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid setting "+key);}
    }
    public JsonNode json() {
        return EngineJson.JSON.createObjectNode().put("policy_version",1).put("lease_ms",leaseMs).put("renew_ms",renewMs)
            .put("db_ms",dbMs).put("http_ms",httpMs).put("ai_ms",aiMs).put("max_attempts",maxAttempts).put("base_ms",baseMs).put("max_ms",maxMs);
    }
    public static ExecutionPolicy parse(String text) {
        var n=EngineJson.read(text);
        if(n==null || !n.isObject() || n.size()!=9 || number(n,"policy_version")!=1)throw new IllegalArgumentException("Unsupported execution policy");
        return new ExecutionPolicy(number(n,"lease_ms"),number(n,"renew_ms"),number(n,"db_ms"),number(n,"http_ms"),number(n,"ai_ms"),number(n,"max_attempts"),number(n,"base_ms"),number(n,"max_ms"));
    }
    private static int number(JsonNode n,String key) {
        var v=n.get(key);if(v==null || !v.isIntegralNumber() || !v.canConvertToInt())throw new IllegalArgumentException("Invalid execution policy");return v.asInt();
    }
    public long backoff(long retryNumber) {
        long result=baseMs;
        for(long i=1;i<retryNumber && result<maxMs;i++)result=Math.min(maxMs,result*2);
        return result;
    }
}
