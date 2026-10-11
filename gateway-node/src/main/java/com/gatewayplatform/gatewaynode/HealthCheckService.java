package com.gatewayplatform.gatewaynode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class HealthCheckService {

    private static final Logger log =  LoggerFactory.getLogger(HealthCheckService.class);
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final LeaderElectionService leaderElectionService;
    private final DatabaseClient db;
    private final WebClient webClient = WebClient.builder().build();

    public HealthCheckService(ReactiveRedisTemplate<String, Object> redisTemplate,
                              LeaderElectionService leaderElectionService, DatabaseClient db) {
        this.redisTemplate = redisTemplate;
        this.leaderElectionService = leaderElectionService;
        this.db = db;
    }

    @Scheduled(fixedRate = 10000)
    public void runHealthChecks() {
        if (!leaderElectionService.isLeader()) {
            return; // only the leader performs health checks
        }
        db.sql("SELECT id, url FROM backend_instances")
                .map(row -> new BackendInstance(row.get("id", UUID.class).toString(), row.get("url", String.class)))
                .all()
                .flatMap(this::checkOne, 10)
                .subscribe(
                        result -> {},
                        e -> log.warn("Heath check cycle failed", e)
                );
    }

    private Mono<Boolean> checkOne(BackendInstance backend){
        return webClient.get()
                .uri(backend.url() + "/health")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .flatMap(resp -> setHealth(backend.id(),true))
                .onErrorResume(e -> setHealth(backend.id(), false));
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
                .defaultIfEmpty(true);
    }
}