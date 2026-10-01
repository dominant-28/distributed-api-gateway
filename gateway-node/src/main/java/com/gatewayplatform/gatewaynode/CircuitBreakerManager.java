package com.gatewayplatform.gatewaynode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerManager {

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerManager(CircuitBreakerRegistry registry){
        this.registry = registry;
    }
     public CircuitBreaker getBreaker(String backendID){
        return registry.circuitBreaker(backendID);
     }
}
