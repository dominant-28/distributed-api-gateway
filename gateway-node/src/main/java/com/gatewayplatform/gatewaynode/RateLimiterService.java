package com.gatewayplatform.gatewaynode;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
@Service
public class RateLimiterService {
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final RedisScript<Long> tokenBucketScript;
    public RateLimiterService(ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = RedisScript.of(new ClassPathResource("token_bucket.lua"), Long.class);
    }

    public Mono<Boolean> isAllowed(String tenantSlug, String routeKey, int rateLimitPerMin) {
        String redisKey = "ratelimit:" + tenantSlug + ":" + routeKey;
        double refillRatePerMin = rateLimitPerMin /60.0;
        double now = System.currentTimeMillis()/ 1000.0;
        return redisTemplate.execute(
                tokenBucketScript,
                List.of(redisKey),
                List.of(String.valueOf(rateLimitPerMin), String.valueOf(refillRatePerMin), String.valueOf(now))
        ).next().map(result -> result == 1L);
    }
}
