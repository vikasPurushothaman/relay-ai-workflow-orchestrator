package com.relay.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class HumanControlController {
    private final HumanControlService service;
    public HumanControlController(HumanControlService service){this.service=service;}
    @GetMapping("/approvals") public List<HumanControlService.ApprovalView> list(@RequestParam(defaultValue="pending") String status){return service.list(status);}
    @PostMapping("/approvals/{id}/approve") public HumanControlService.RunResult approve(@PathVariable String id,HttpServletRequest request)throws IOException {empty(request);return service.decide(id,true);}
    @PostMapping("/approvals/{id}/reject") public HumanControlService.RunResult reject(@PathVariable String id,HttpServletRequest request)throws IOException {empty(request);return service.decide(id,false);}
    @PostMapping("/runs/{id}/cancel") public ResponseEntity<HumanControlService.RunResult> cancel(@PathVariable String id,HttpServletRequest request)throws IOException {
        empty(request);var result=service.cancel(id);return ResponseEntity.status(result.status().equals("running")?202:200).body(result);
    }
    private static void empty(HttpServletRequest request)throws IOException {if(request.getInputStream().read()!=-1)throw new ApiFailure(ApiFailure.Reason.INVALID_INPUT);}
}
