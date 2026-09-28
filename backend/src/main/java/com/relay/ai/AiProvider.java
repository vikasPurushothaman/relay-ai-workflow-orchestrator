package com.relay.ai;

import com.relay.engine.EngineStore;
import com.relay.engine.HttpTransport;
import tools.jackson.databind.JsonNode;

/** Implementations never mutate engine state; requests are frozen before any invocation. */
public interface AiProvider {
    JsonNode prepare(JsonNode parameters);
    JsonNode repair(JsonNode original,String validationCode);
    HttpTransport.Outcome invoke(EngineStore.Prepared prepared);
}
