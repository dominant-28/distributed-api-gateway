package com.gatewayplatform.gatewaynode;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class ProxyController {
    private final RouteLookupService routeLookupService;
    private final WebClient webClient = WebClient.builder().baseUrl("http://localhost:7000").build();

    public ProxyController(RouteLookupService routeLookupService) {
        this.routeLookupService = routeLookupService;
    }
    @GetMapping("/{tenantSlug}/api/products/{id}")
    public Mono<String> proxyToTenantBackend(@PathVariable String tenantSlug,@PathVariable String id){
        return routeLookupService.findBackendUrl(tenantSlug, "/api/products")
                .flatMap(backendUrl -> webClient.get()
                        .uri(backendUrl + "/api/products/{id}", id)
                        .retrieve().bodyToMono(String.class)
                );
    }
}
