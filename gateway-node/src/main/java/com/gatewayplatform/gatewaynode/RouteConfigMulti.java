package com.gatewayplatform.gatewaynode;

import java.util.List;

public record RouteConfigMulti(List<BackendInstance> backends, int rateLimitPerMin) {
}
