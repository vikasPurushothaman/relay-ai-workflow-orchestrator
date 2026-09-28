package com.relay.engine;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

public final class EngineJson {
    private EngineJson() {}
    public static final int MAX_BYTES=1024*1024;
    public static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BYTES).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public static JsonNode read(String text) { return JSON.readTree(text); }
    public static String error(String code) { return JSON.createObjectNode().put("code",code).toString(); }
}
