package com.gatewayplatform.gatewaynode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoadBalancerService {

    private final HealthCheckService healthCheckService;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final CircuitBreakerManager circuitBreakerManager;
    public LoadBalancerService(HealthCheckService healthCheckService, CircuitBreakerManager circuitBreakerManager) {
        this.healthCheckService = healthCheckService;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    public Mono<BackendInstance> pickBackend(List<BackendInstance> backends){
        return Flux.fromIterable(backends)
                .filterWhen(b -> healthCheckService.isHealthy(b.id()))
                .filter(b -> {
                    CircuitBreaker cb = circuitBreakerManager.getBreaker(b.id());
                    return cb.getState() != CircuitBreaker.State.OPEN;
                })
                .collectList()
                .flatMap( healthy -> {
                    if(healthy.isEmpty()){
                        return Mono.error(new RuntimeException("No Backends available"));

                    }
                    int index = Math.abs(counter.getAndIncrement() % healthy.size());
                    return Mono.just(healthy.get(index));
                });
    }
}
