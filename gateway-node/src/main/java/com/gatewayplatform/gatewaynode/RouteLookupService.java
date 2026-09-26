package com.gatewayplatform.gatewaynode;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RouteLookupService {

    private final DatabaseClient databaseClient;

    public RouteLookupService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<RouteConfigMulti> findRouteConfigMulti(String tenantSlug, String pathPattern) {
        String rateLimitSql = """
        SELECT r.rate_limit_per_min, r.backend_pool_id
        FROM tenants t
        JOIN routes r ON r.tenant_id = t.id
        WHERE t.slug = :slug AND r.path_pattern = :pathPattern
        LIMIT 1
        """;

        return databaseClient.sql(rateLimitSql)
                .bind("slug", tenantSlug)
                .bind("pathPattern", pathPattern)
                .map(row -> new Object[]{
                        row.get("rate_limit_per_min", Integer.class),
                        row.get("backend_pool_id", java.util.UUID.class)
                })
                .one()
                .flatMap(result -> {
                    int rateLimit = (int) result[0];
                    java.util.UUID poolId = (java.util.UUID) result[1];

                    String backendsSql = "SELECT id, url FROM backend_instances WHERE backend_pool_id = :poolId";

                    return databaseClient.sql(backendsSql)
                            .bind("poolId", poolId)
                            .map(row -> new BackendInstance(
                                    row.get("id", java.util.UUID.class).toString(),
                                    row.get("url", String.class)
                            ))
                            .all()
                            .collectList()
                            .map(backends -> new RouteConfigMulti(backends, rateLimit));
                });
    }
}