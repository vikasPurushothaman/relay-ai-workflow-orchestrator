package com.relay.api;

import com.relay.engine.EngineJson;
import com.relay.persistence.TriggerType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class TriggerController {
    public record Accepted(String run_id) {}
    private final AcceptanceService service;
    public TriggerController(AcceptanceService service){this.service=service;}
    @PostMapping(value="/workflows/{workflowId}/trigger",consumes="application/json")
    public ResponseEntity<Accepted> manual(@PathVariable String workflowId,HttpServletRequest request) throws IOException {
        JsonNode body=body(request);
        if(!body.isObject() || body.size()!=1 || !body.has("input") || !body.get("input").isObject())throw new ApiFailure(ApiFailure.Reason.INVALID_INPUT);
        return accepted(service.accept(workflowId,body.get("input"),TriggerType.manual,null));
    }
    @PostMapping(value="/hooks/{workflowId}",consumes="application/json")
    public ResponseEntity<Accepted> hook(@PathVariable String workflowId,HttpServletRequest request) throws IOException {
        var secrets=Collections.list(request.getHeaders("X-Relay-Secret"));
        if(secrets.size()!=1 || secrets.getFirst().isBlank() || secrets.getFirst().length()>1024)throw new ApiFailure(ApiFailure.Reason.WEBHOOK_AUTH);
        return accepted(service.accept(workflowId,body(request),TriggerType.webhook,secrets.getFirst()));
    }
    private ResponseEntity<Accepted> accepted(String id){return ResponseEntity.status(202).header("Cache-Control","no-store").body(new Accepted(id));}
    private JsonNode body(HttpServletRequest request) throws IOException {
        if(request.getContentLengthLong()>EngineJson.MAX_BYTES)throw new ApiFailure(ApiFailure.Reason.TOO_LARGE);
        byte[] bytes=request.getInputStream().readNBytes(EngineJson.MAX_BYTES+1);
        if(bytes.length>EngineJson.MAX_BYTES)throw new ApiFailure(ApiFailure.Reason.TOO_LARGE);
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode result=EngineJson.read(text);
            if(result==null || result.isMissingNode())throw new IllegalArgumentException();
            return result;
        } catch(RuntimeException | CharacterCodingException ex){throw new ApiFailure(ApiFailure.Reason.INVALID_INPUT);}
    }
}
