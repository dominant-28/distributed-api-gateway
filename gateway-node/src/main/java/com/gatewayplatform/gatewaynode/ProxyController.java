package com.gatewayplatform.gatewaynode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@RestController
public class ProxyController {
    private final RouteLookupService routeLookupService;
    private final RateLimiterService rateLimiterService;
    private final LoadBalancerService loadBalancerService;
    private final TraceService traceService;
    private final CircuitBreakerManager circuitBreakerManager;
    private final WebClient webClient = WebClient.builder().build();

    public ProxyController(RouteLookupService routeLookupService, RateLimiterService rateLimiterService, LoadBalancerService loadBalancerService, TraceService traceService, CircuitBreakerManager circuitBreakerManager) {
        this.routeLookupService = routeLookupService;
        this.rateLimiterService = rateLimiterService;
        this.loadBalancerService = loadBalancerService;
        this.traceService = traceService;
        this.circuitBreakerManager = circuitBreakerManager;
    }
    @GetMapping("/{tenantSlug}/api/products/{id}")
    public Mono<String> proxyToTenantBackend(@PathVariable String tenantSlug, @PathVariable String id, ServerWebExchange exchange){
        String traceId = exchange.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
        long startNanos = System.nanoTime();
        AtomicReference<String> chosenBackend = new AtomicReference<>("none");
        return routeLookupService.findRouteConfigMulti(tenantSlug, "/api/products")
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")))
                .flatMap( routeConfig -> rateLimiterService.isAllowed(tenantSlug, "/api/products",routeConfig.rateLimitPerMin())
                        .flatMap(allowed -> {
                            if (!allowed) {
                                return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded"));
                            }
                            return loadBalancerService.pickBackend(routeConfig.backends())
                                    .flatMap(backend -> {
                                        chosenBackend.set(backend.url());
                                        return callBackendWithCircuitBreaker(backend, id, traceId);
                                    });
                        })
                )
                .doOnSuccess(body -> traceService.record(traceId, tenantSlug, chosenBackend.get(), 200, startNanos))
                .doOnError(e -> traceService.record(traceId, tenantSlug, chosenBackend.get(), statusFrom(e), startNanos));
    }

    private int statusFrom(Throwable e) {
        if(e instanceof ResponseStatusException rse) {
            return rse.getStatusCode().value();
        }
        return 500;
    }

    private Mono<String> callBackendWithCircuitBreaker(BackendInstance backend, String id, String traceId) {
        CircuitBreaker breaker = circuitBreakerManager.getBreaker(backend.id());

        Mono<String> call = webClient.get()
                .uri(backend.url() + "/api/products/{id}", id)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .retrieve()
                .onStatus(status -> status.is5xxServerError(),
                        response -> Mono.error(new RuntimeException("Backend 5xx error")))
                .bodyToMono(String.class);

        return call.transform(CircuitBreakerOperator.of(breaker));
    }
}
