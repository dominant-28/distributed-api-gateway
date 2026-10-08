package com.gatewayplatform.gatewaynode;

import java.time.OffsetDateTime;

public record TraceRecord(String traceId, String tenantSlug, String backendUrl,
                          int statusCode, int latencyMs, String gatewayNodeId,
                          OffsetDateTime createdAt) {}