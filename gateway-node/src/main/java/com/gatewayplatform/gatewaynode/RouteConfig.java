package com.gatewayplatform.gatewaynode;

public record RouteConfig(String backendUrl, int rateLimitPerMin) {
}
