package com.gatewayplatform.gatewaynode;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class ProxyController {

    private final WebClient webClient = WebClient.builder().baseUrl("http://localhost:7000").build();

    @GetMapping("/proxy/api/products/{id}")
    public Mono<String> proxyToBackendA(@PathVariable String id){
        return webClient.get().uri("/api/products/{id}",id).retrieve().bodyToMono(String.class);
    }
}
