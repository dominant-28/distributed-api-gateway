package com.gatewayplatform.gatewaynode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
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
    private final CircuitBreakerManager circuitBreakerManager;
    private final WebClient webClient = WebClient.builder().build();

    public ProxyController(RouteLookupService routeLookupService, RateLimiterService rateLimiterService, LoadBalancerService loadBalancerService, CircuitBreakerManager circuitBreakerManager) {
        this.routeLookupService = routeLookupService;
        this.rateLimiterService = rateLimiterService;
        this.loadBalancerService = loadBalancerService;
        this.circuitBreakerManager = circuitBreakerManager;
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
                                    .flatMap(backend -> callBackendWithCircuitBreaker(backend, id));
                        })
                );
    }

    private Mono<String> callBackendWithCircuitBreaker(BackendInstance backend, String id) {
        CircuitBreaker breaker = circuitBreakerManager.getBreaker(backend.id());

        Mono<String> call = webClient.get()
                .uri(backend.url() + "/api/products/{id}", id)
                .retrieve()
                .onStatus(status -> status.is5xxServerError(),
                        response -> Mono.error(new RuntimeException("Backend 5xx error")))
                .bodyToMono(String.class);

        return call.transform(CircuitBreakerOperator.of(breaker));
    }
}
