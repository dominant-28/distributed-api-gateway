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
    public Mono<String> findBackendUrl(String tenantSlug, String pathPattern){
        String sql = """
            SELECT bi.url
            FROM tenants t
            JOIN routes r ON r.tenant_id = t.id
            JOIN backend_instances bi ON bi.backend_pool_id = r.backend_pool_id
            WHERE t.slug = :slug AND r.path_pattern = :pathPattern
            LIMIT 1
            """;
        return databaseClient.sql(sql)
                .bind("slug", tenantSlug)
                .bind("pathPattern", pathPattern)
                .map(row -> row.get("url",String.class))
                .one();
    }
}
