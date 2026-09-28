package com.relay.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class RunQueryController {
    private final RunQueryService service;
    public RunQueryController(RunQueryService service){this.service=service;}
    @GetMapping("/runs") public RunQueryService.RunPage list(@RequestParam MultiValueMap<String,String> params){return service.list(params);}
    @GetMapping("/runs/{id}") public RunQueryService.RunDetail get(@PathVariable String id,@RequestParam MultiValueMap<String,String> params){return service.get(id,params);}
}
