package com.gatewayplatform.gatewaynode;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;

@Service
public class TraceService {

    public static final Logger log = LoggerFactory.getLogger(TraceService.class);
    public final DatabaseClient db;
    public final String nodeId;
    public TraceService(DatabaseClient db, @Value("${node.id}") String nodeId) {
        this.db = db;
        this.nodeId = nodeId;
    }

    public void record(String traceId, String tenantSlug, String backendUrl,
                       int statusCode, long startNanos){
        int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);

        db.sql("""
                INSERT INTO request_traces
                  (trace_id, tenant_slug, backend_url, status_code, latency_ms, gateway_node_id)
                VALUES (:traceId, :tenant, :backend, :status, :latency, :node)
                """)
                .bind("traceId", traceId)
                .bind("tenant", tenantSlug)
                .bind("backend", backendUrl)
                .bind("status", statusCode)
                .bind("latency", latencyMs)
                .bind("node", nodeId)
                .fetch().rowsUpdated()
                .subscribe(n -> {}, e -> log.warn("Failed to write trace {}", traceId, e));
    }
    public Flux<TraceRecord> findByTraceId(String traceId) {
        return db.sql("""
                SELECT trace_id, tenant_slug, backend_url, status_code, latency_ms,
                       gateway_node_id, created_at
                FROM request_traces WHERE trace_id = :traceId ORDER BY created_at
                """)
                .bind("traceId", traceId)
                .map(row -> new TraceRecord(
                        row.get("trace_id", String.class),
                        row.get("tenant_slug", String.class),
                        row.get("backend_url", String.class),
                        row.get("status_code", Integer.class),
                        row.get("latency_ms", Integer.class),
                        row.get("gateway_node_id", String.class),
                        row.get("created_at", OffsetDateTime.class)))
                .all();

    }
}
