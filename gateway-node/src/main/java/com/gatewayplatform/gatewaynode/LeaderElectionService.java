package com.gatewayplatform.gatewaynode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LeaderElectionService {
    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);
    private static final String LEADER_KEY = "leader:gateway-cluster";
    private static final Duration LEASE_TTL = Duration.ofSeconds(10);

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final String nodeId;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);

    public LeaderElectionService(ReactiveRedisTemplate<String, Object> redisTemplate,
                                 @Value("${node.id}") String nodeId) {
        this.redisTemplate = redisTemplate;
        this.nodeId = nodeId;
    }

    @Scheduled(fixedRate = 3000) // try/renew every 3 seconds; TTL is 10s
    public void tryAcquireOrRenewLeadership() {
        if (isLeader.get()) {
            // Already leader: renew the lease, but only if we still own it
            redisTemplate.opsForValue().get(LEADER_KEY)
                    .defaultIfEmpty("")
                    .flatMap(currentHolder -> {
                        if (nodeId.equals(currentHolder)) {
                            return redisTemplate.opsForValue().set(LEADER_KEY, nodeId, LEASE_TTL);
                        } else {
                            // Someone else took over (shouldn't normally happen, but be safe)
                            isLeader.set(false);
                            return reactor.core.publisher.Mono.just(false);
                        }
                    })
                    .subscribe();
        } else {
            // Not leader: try to acquire (SET NX EX)
            redisTemplate.opsForValue()
                    .setIfAbsent(LEADER_KEY, nodeId, LEASE_TTL)
                    .subscribe(acquired -> {
                        if (acquired) {
                            isLeader.set(true);
                            log.info("[{}] became LEADER", nodeId);
                        }
                    });
        }
    }

    public boolean isLeader() {
        return isLeader.get();
    }

    public String nodeId() {
        return nodeId;
    }
}