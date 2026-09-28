package com.relay.api;

import com.relay.workflow.DefinitionParser;
import com.relay.workflow.DefinitionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/workflows")
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class WorkflowController {
    private final WorkflowService service;
    private final PublicationService publication;
    public WorkflowController(WorkflowService service,PublicationService publication) { this.service=service;this.publication=publication; }
    @PostMapping("/{workflowId}/publish")
    public WorkflowService.Detail publish(@PathVariable String workflowId,HttpServletRequest request) throws IOException {
        if(request.getInputStream().read()!=-1) throw new ApiFailure(ApiFailure.Reason.INVALID_INPUT);
        return publication.publish(workflowId);
    }
    @GetMapping public List<WorkflowService.Summary> list() { return service.list(); }
    @GetMapping("/{workflowId}") public WorkflowService.Detail get(@PathVariable String workflowId) { return service.get(workflowId); }
    @PostMapping(consumes="application/json") public ResponseEntity<WorkflowService.Detail> create(HttpServletRequest request) throws IOException {
        return ResponseEntity.status(201).body(service.create(body(request)));
    }
    @PutMapping(value="/{workflowId}",consumes="application/json") public WorkflowService.Detail update(@PathVariable String workflowId,HttpServletRequest request) throws IOException {
        return service.update(workflowId,body(request));
    }
    private String body(HttpServletRequest request) throws IOException {
        if(request.getContentLengthLong()>DefinitionParser.MAX_BYTES) throw new ApiFailure(ApiFailure.Reason.TOO_LARGE);
        byte[] bytes=request.getInputStream().readNBytes(DefinitionParser.MAX_BYTES+1);
        if(bytes.length>DefinitionParser.MAX_BYTES) throw new ApiFailure(ApiFailure.Reason.TOO_LARGE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch(CharacterCodingException e) { throw new DefinitionException("invalid_utf8","$"); }
    }
}
