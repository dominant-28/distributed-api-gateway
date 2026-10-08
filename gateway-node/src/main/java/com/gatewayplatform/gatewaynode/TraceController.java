package com.gatewayplatform.gatewaynode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class TraceController {

    private final TraceService traceService;
    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }
    @GetMapping("/traces/{traceId}")
    public Flux<TraceRecord> getTraces(@PathVariable String traceId) {
        return traceService.findByTraceId(traceId);
    }
}
