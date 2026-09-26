package com.gatewayplatform.gatewaynode;

import io.r2dbc.postgresql.message.backend.BackendKeyData;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Service
public class HealthCheckService {

    public final ReactiveRedisTemplate<String, Object> redisTemplate;
    public final WebClient webClient = WebClient.builder().build();
    public HealthCheckService(ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<BackendInstance> KnownBackends = List.of(
            new BackendInstance("47aa7d01-9499-4400-8c66-99b35cbcbff8","http://localhost:7000"),
            new BackendInstance("d0440978-7996-428d-8536-1349ce346434","http://localhost:7001")
    );

    @Scheduled(fixedRate = 10000)
    public void runHealthCheck(){
        for ( BackendInstance backend : KnownBackends){
            webClient.get()
                    .uri(backend.url() + "/health")
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(3))
                    .flatMap(resp -> setHealth(backend.id(), true))
                    .onErrorResume(e -> setHealth(backend.id(), false))
                    .subscribe();
        }
    }

    private reactor.core.publisher.Mono<Boolean> setHealth(String backendId, boolean healthy) {
        String key = "health:" + backendId;
        return redisTemplate.opsForValue()
                .set(key, healthy ? "HEALTHY" : "UNHEALTHY", Duration.ofSeconds(30))
                .thenReturn(healthy);
    }

    public reactor.core.publisher.Mono<Boolean> isHealthy(String backendId) {
        String key = "health:" + backendId;
        return redisTemplate.opsForValue().get(key)
                .map(val -> "HEALTHY".equals(val))
                .defaultIfEmpty(true); // assume healthy if not yet checked
    }

}
