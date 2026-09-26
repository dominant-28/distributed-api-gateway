package com.gatewayplatform.gatewaynode;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoadBalancerService {

    private final HealthCheckService healthCheckService;
    private final AtomicInteger counter = new AtomicInteger(0);

    public LoadBalancerService(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    public Mono<BackendInstance> pickBackend(List<BackendInstance> backends){
        return Flux.fromIterable(backends)
                .filterWhen(b -> healthCheckService.isHealthy(b.id()))
                .collectList()
                .flatMap( healthy -> {
                    if(healthy.isEmpty()){
                        return Mono.error(new RuntimeException("No healthy Backends available"));

                    }
                    int index = Math.abs(counter.getAndIncrement() % healthy.size());
                    return Mono.just(healthy.get(index));
                });
    }
}
