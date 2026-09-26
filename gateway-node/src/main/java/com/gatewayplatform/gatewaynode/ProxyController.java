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
    private final LoadBalancerService loadBalancerService;
    private final WebClient webClient = WebClient.builder().build();

    public ProxyController(RouteLookupService routeLookupService, RateLimiterService rateLimiterService, LoadBalancerService loadBalancerService) {
        this.routeLookupService = routeLookupService;
        this.rateLimiterService = rateLimiterService;
        this.loadBalancerService = loadBalancerService;
    }
    @GetMapping("/{tenantSlug}/api/products/{id}")
    public Mono<String> proxyToTenantBackend(@PathVariable String tenantSlug,@PathVariable String id){
        return routeLookupService.findRouteConfigMulti(tenantSlug, "/api/products")
                .flatMap( routeConfig -> rateLimiterService.isAllowed(tenantSlug, "/api/products",routeConfig.rateLimitPerMin())
                        .flatMap(allowed -> {
                            if (!allowed) {
                                return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded"));
                            }
                            return loadBalancerService.pickBackend(routeConfig.backends())
                                    .flatMap(backend -> webClient.get().uri(backend.url() + "/api/products/{id}", id)
                                            .retrieve()
                                            .bodyToMono(String.class)
                                    );
                        })
                );
    }
}
