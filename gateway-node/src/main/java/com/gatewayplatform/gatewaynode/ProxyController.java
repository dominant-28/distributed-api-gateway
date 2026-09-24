package com.gatewayplatform.gatewaynode;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
public class ProxyController {
    private final RouteLookupService routeLookupService;
    private final RateLimiterService rateLimiterService;
    private final WebClient webClient = WebClient.builder().build();

    public ProxyController(RouteLookupService routeLookupService,  RateLimiterService rateLimiterService) {
        this.routeLookupService = routeLookupService;
        this.rateLimiterService = rateLimiterService;
    }
    @GetMapping("/{tenantSlug}/api/products/{id}")
    public Mono<String> proxyToTenantBackend(@PathVariable String tenantSlug,@PathVariable String id){
        return routeLookupService.findRouteConfig(tenantSlug, "/api/products")
                .flatMap( routeConfig -> rateLimiterService.isAllowed(tenantSlug, "/api/products",routeConfig.rateLimitPerMin())
                        .flatMap(allowed -> {
                            if(!allowed){
                                return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded"));
                            }
                            return  webClient.get().uri(routeConfig.backendUrl() + "/api/products/{id}", id).retrieve().bodyToMono(String.class);
                        })
                );
    }
}
