# Distributed API Gateway Platform — Full Project Design Document

> **Purpose of this document:** This is the single source of truth for the project. It is written so that any developer or AI assistant can read it and have complete context — what we are building, why, how every piece works, how to run it locally, and how to deploy it. Any future conversation about this project (with a human or an AI model) should start from this doc.

---

## 1. One-Line Description

A **multi-tenant, distributed API Gateway platform** — a hosted service that any team can point their existing backend at to get rate limiting, load balancing, circuit breaking, and request tracing, without running any of that infrastructure themselves. Think "a small, self-hosted-but-shared version of Kong / AWS API Gateway," built to genuinely understand and demonstrate distributed systems concepts (leader election, distributed rate limiting, failure recovery), not just to look impressive.

---

## 2. Motivation / Why This Project

- Built as a portfolio project targeting backend/distributed-systems roles at top product companies (Amazon, Uber, etc.).
- Goal: not a toy CRUD app — a project that demonstrates real distributed systems engineering: consensus/coordination (leader election), atomic distributed state (rate limiting), fault tolerance (circuit breakers, health checks), and observability (tracing).
- Explicit design principle: **every gateway node is stateless and disposable.** No single node is a single point of failure. This property must hold from day one, even when running only 2 instances due to free-tier hosting limits — so scaling to N instances later requires **zero code changes**, only more replicas.
- Must be a genuinely usable product, not just a demo: a stranger should be able to sign up, register their own backend URLs, and route real traffic through it.

---

## 3. Who Owns What (Critical Mental Model)

This is the most important clarification in the whole project — get this wrong and the architecture breaks.

| Component | Owned/Run By |
|---|---|
| Gateway nodes (the proxy processes) | **Us** (the platform) |
| Redis (coordination/ephemeral state) | **Us** |
| Postgres (durable config/tenant data) | **Us** |
| Dashboard/UI | **Us** |
| The user's actual backend application (e.g. their e-commerce API) | **The user (tenant)** — their own servers, their own scaling, their own deploys |
| Health status *of* the user's backend | **We observe it**, we never control or provision it |

**We are a shared, multi-tenant reverse-proxy / traffic-management layer sitting in front of URLs the user already owns and runs.** We never spin up, deploy, or scale anyone's backend compute. We only route traffic, enforce policy (rate limits, circuit breaking), and observe health.

---

## 4. Core Feature Set (Final — Do Not Scope-Creep Beyond This Without Reason)

| # | Feature | What It Proves / Why It's Real |
|---|---|---|
| 1 | **Distributed rate limiting** (token bucket, per-tenant, per-API-key, per-route) | Atomic distributed counters under concurrency; algorithm tradeoffs (token bucket vs sliding window) |
| 2 | **Load balancing** across a tenant's own backend instances (round robin, least-connections) + active health checks | Traffic distribution; failure detection; graceful degradation |
| 3 | **Circuit breaker** per backend (closed → open → half-open) | Prevents cascading failure; protects an already-struggling backend from being hammered |
| 4 | **Leader election** (Redis lease-based) for singleton background jobs (health checking, periodic cleanup) | Coordination without duplicated work; automatic failover if the leader dies |
| 5 | **Distributed tracing** (correlation/trace ID generated at the gateway, propagated to backend, visualized as a timeline) | End-to-end observability across the request path |
| 6 | **Multi-tenancy** (isolated config, isolated rate limits, isolated metrics per organization) | Real product usability — the reason this is "a platform," not a script |
| 7 | **Two-layer authentication** (see Section 9) | Product auth (who can log into the dashboard) vs Edge auth (JWT/API-key validation on proxied requests) |
| 8 | **Hot-reloadable config** (no gateway restart needed when a user changes their routes/limits) | Real operational property production gateways need |

Explicitly **out of scope for v1** (mention as "future work," do not build unless core is rock-solid): request/response body transformation, canary/traffic-splitting, TLS termination/mTLS, dynamic service discovery via heartbeat registration, request queuing/backpressure. These are legitimate real gateway features and can be named as a roadmap in the resume/README, but should not dilute engineering depth on the core 8.

---

## 5. Tech Stack (Final Decision)

| Layer | Choice | Why |
|---|---|---|
| Gateway core + Admin API | **Java + Spring Boot (Spring WebFlux, reactive/non-blocking)** | Non-blocking I/O needed for a gateway handling many concurrent connections; mirrors real-world **Spring Cloud Gateway** (built by Pivotal/VMware) — legitimate precedent, not a toy choice |
| Circuit breaker / rate limiter / retry primitives | **Resilience4j** | The actual industry-standard Java library for this; used in real production systems — naming it correctly is a credibility signal |
| Product auth (dashboard login) | **Spring Security + JWT**, or Supabase Auth (managed, free tier) to save time | Legit tradeoff either way — if using Supabase Auth, state explicitly: "used managed auth to focus engineering effort on the distributed systems core" |
| Durable data (tenants, users, API keys, route/backend config, historical data) | **PostgreSQL** (Supabase free tier or Neon free tier) | ACID, relational, structured, doesn't need to be blazing fast per-request (cached in-memory/Redis on the hot path) |
| Ephemeral/coordination data (rate limit counters, leader lease, live health status) | **Redis** (Upstash free tier) | Fast, supports atomic Lua scripts, pub/sub for config propagation, TTL-native (perfect for leases and rate-limit windows) |
| Dashboard frontend | **TypeScript + React + Vite**, deployed on Vercel free tier | Type safety, fast dev loop, free hosting |
| Dummy/test backends (for local dev & demo) | Small Node/Express or Spring Boot apps, one deliberately flaky (randomized delay/500s) | Needed to exercise circuit breaker + health checks in demos |
| Containerization | **Docker + Docker Compose** | One-command local run; same images usable in deployment |
| Metrics/tracing visualization | Start with a **hand-rolled live dashboard** (WebSocket-pushed events into React charts); Prometheus + Grafana as a stretch goal later | Zero extra infra to start; full control over what's shown in demo |

---

## 6. High-Level Architecture

```
                                   ┌─────────────────────────┐
                                   │   Dashboard (React/TS)   │
                                   │  - Tenant signup/login   │
                                   │  - Configure routes,     │
                                   │    backends, limits      │
                                   │  - Live metrics/traces   │
                                   │  - "Break it" chaos btns │
                                   └────────────┬─────────────┘
                                                │ REST + WebSocket
                                                ▼
                                   ┌─────────────────────────┐
                                   │     Admin/Control API     │
                                   │ (Spring Boot service)      │
                                   │ - Auth (product-level)     │
                                   │ - CRUD tenant config        │
                                   │ - Writes to Postgres +       │
                                   │   publishes to Redis pub/sub  │
                                   └────────────┬─────────────┘
                                                │
                    ┌───────────────────────────┼───────────────────────────┐
                    ▼                           ▼                           ▼
             ┌─────────────┐             ┌─────────────┐             ┌─────────────┐
             │  Postgres    │             │    Redis     │             │  (future:    │
             │ (Supabase)   │◄───────────►│  (Upstash)   │             │  Kafka/etcd) │
             │ durable      │   synced    │ ephemeral +  │             │              │
             │ config       │   on change │ coordination │             │              │
             └─────────────┘             └──────┬───────┘             └─────────────┘
                                                 │ pub/sub (config change events,
                                                 │ leader heartbeat, health status)
                    ┌────────────────────────────┼────────────────────────────┐
                    ▼                            ▼                            ▼
             ┌─────────────┐             ┌─────────────┐             ┌─────────────┐
             │ Gateway Node │             │ Gateway Node │             │ Gateway Node │
             │      1       │             │      2       │             │   N (future) │
             │ (stateless,  │             │ (stateless,  │             │              │
             │  Spring      │             │  Spring      │             │              │
             │  WebFlux)    │             │  WebFlux)    │             │              │
             └──────┬───────┘             └──────┬───────┘             └─────────────┘
                    │                            │
                    │      proxied requests      │
                    ▼                            ▼
        ┌───────────────────────┐   ┌───────────────────────┐
        │  Tenant A's backends    │   │  Tenant B's backends    │
        │  (owned/run by Tenant A)│   │  (owned/run by Tenant B)│
        │  orga-backend-1.com     │   │  orgb-api.com           │
        │  orga-backend-2.com     │   │                          │
        └───────────────────────┘   └───────────────────────┘
```

**Key property:** any gateway node can handle any tenant's request. Nodes hold no tenant-specific state that isn't a disposable, refreshable in-memory cache of what's in Redis/Postgres. Kill any node — no data loss, no broken tenant.

---

## 7. Data Model

### 7.1 Postgres schema (durable, relational)

```sql
-- Organizations / tenants
CREATE TABLE tenants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  slug TEXT UNIQUE NOT NULL,        -- used in the routing URL, e.g. "org-a-123"
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Dashboard users (product-level auth)
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  email TEXT UNIQUE NOT NULL,
  password_hash TEXT,               -- null if using managed auth (Supabase)
  role TEXT DEFAULT 'admin',        -- admin, viewer, etc.
  created_at TIMESTAMPTZ DEFAULT now()
);

-- API keys issued to a tenant (used by their client apps to call the gateway)
CREATE TABLE api_keys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  key_hash TEXT UNIQUE NOT NULL,    -- store hash, never plaintext
  label TEXT,
  active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Routes a tenant has configured
CREATE TABLE routes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  path_pattern TEXT NOT NULL,       -- e.g. "/api/products/*"
  backend_pool_id UUID REFERENCES backend_pools(id),
  rate_limit_per_min INT DEFAULT 100,
  rate_limit_algorithm TEXT DEFAULT 'token_bucket', -- or 'sliding_window'
  require_auth BOOLEAN DEFAULT true,
  tracing_enabled BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

-- Backend pools (a set of URLs owned by the tenant)
CREATE TABLE backend_pools (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  name TEXT NOT NULL,
  load_balance_strategy TEXT DEFAULT 'round_robin', -- or 'least_connections'
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE backend_instances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  backend_pool_id UUID REFERENCES backend_pools(id),
  url TEXT NOT NULL,
  weight INT DEFAULT 1,             -- for weighted round robin
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Circuit breaker config per backend instance
CREATE TABLE circuit_breaker_config (
  backend_instance_id UUID REFERENCES backend_instances(id) PRIMARY KEY,
  failure_threshold_pct INT DEFAULT 50,
  cooldown_seconds INT DEFAULT 30,
  min_requests_to_trip INT DEFAULT 10
);

-- Historical request logs (for trace timeline view; can also be a time-series store later)
CREATE TABLE request_traces (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  trace_id TEXT NOT NULL,
  route_id UUID REFERENCES routes(id),
  backend_instance_id UUID REFERENCES backend_instances(id),
  status_code INT,
  latency_ms INT,
  gateway_node_id TEXT,
  timestamp TIMESTAMPTZ DEFAULT now()
);
```

### 7.2 Redis key patterns (ephemeral, coordination, hot-path)

```
# Rate limiting (token bucket state per tenant + api key + route)
ratelimit:{tenantId}:{apiKeyId}:{routeId}         -> hash { tokens, last_refill_ts }

# Leader election
leader:gateway-cluster                             -> node_id  (SET NX EX <ttl>)

# Backend health status (written by leader, read by all nodes)
health:{tenantId}:{backendInstanceId}              -> "HEALTHY" | "UNHEALTHY"  (with TTL)

# Circuit breaker live state
circuit:{tenantId}:{backendInstanceId}             -> "CLOSED" | "OPEN" | "HALF_OPEN"

# Config change notifications (pub/sub channel)
channel: config:updated                            -> message: { tenantId, configType }

# Cached hot config (optional read-through cache to avoid hitting Postgres every time)
config:{tenantId}:routes                           -> JSON blob of tenant's routes (refreshed on change)
```

---

## 8. Request Lifecycle (Detailed, End-to-End)

Example: Tenant "Org A" (`slug: org-a-123`) has registered route `/api/products/*` pointing to a backend pool with two URLs. A client calls:

```
GET https://yourgateway.com/org-a-123/api/products/42
Header: X-API-Key: sk_orga_xxx
```

Step by step:

1. **Request hits the load balancer**, which forwards to any available gateway node (node 1 or node 2 — doesn't matter, both are stateless and identical).
2. **Gateway extracts `tenantId`** (`org-a-123`) from the URL path.
3. **Config lookup**: gateway checks its **local in-memory cache** for Org A's route config first (refreshed via Redis pub/sub whenever it changes — never re-fetched from Postgres per request). Cache miss → read from Redis → fallback to Postgres if not even in Redis (rare, e.g., cold start).
4. **API key validation**: hash the provided key, check against cached/Redis-stored active keys for this tenant. Invalid/inactive → `401` immediately.
5. **Rate limit check**: atomic Redis Lua script does the token-bucket check-and-decrement for `ratelimit:org-a-123:{apiKeyId}:{routeId}` in one atomic operation (critical: must be atomic across concurrent gateway nodes, or two nodes could both read "1 token left" and both allow the request). Over limit → `429 Too Many Requests`, request never leaves the gateway.
6. **Circuit breaker + backend selection**: for each backend in Org A's pool, check `circuit:{tenantId}:{backendInstanceId}`. Skip any `OPEN` circuit. Among remaining healthy backends, apply the configured load-balancing strategy (round robin / least-connections) to pick one.
7. **Trace ID generation**: attach a new (or propagate an existing) `X-Trace-Id` header.
8. **Forward the request** to the chosen backend URL, preserving method/body/relevant headers, injecting the trace ID.
9. **Backend responds** (or times out / errors).
10. **Gateway records the outcome**: update circuit breaker failure/success counters for that backend, log latency + status to `request_traces` (async, non-blocking — don't hold up the response for this write), then return the response to the client.

### Added latency budget (target)

| Step | Approx. cost |
|---|---|
| Config/API-key lookup (in-memory cache) | ~0.1–1 ms |
| Rate limit check (Redis, same region) | ~1–3 ms |
| Routing/circuit-breaker decision (in-memory) | <0.1 ms |
| **Total gateway overhead** | **~2–5 ms** (target, to be benchmarked and reported as a real metric) |

---

## 9. Authentication — Two Distinct Layers (Do Not Conflate)

### Layer 1: Product auth (who can use the dashboard)
- Signup/login for a tenant's team members.
- Implemented via Spring Security + JWT, or a managed provider (Supabase Auth) to save engineering time — explicitly state this tradeoff if used.
- Governs access to the **Admin/Control API** (viewing/editing routes, backends, limits, traces).

### Layer 2: Edge auth (what the gateway enforces on proxied traffic)
- **API-key validation**: the tenant's own client apps authenticate to the gateway using an issued API key (`X-API-Key` header).
- **JWT validation (optional per route)**: gateway can validate a JWT's signature/expiry/claims *before* forwarding to the tenant's backend, offloading that work from their service.
- This is a **feature the gateway provides to tenants**, separate from how tenants log into the dashboard itself.

Stating this distinction clearly (in the doc, README, and interviews) is itself a signal of understanding — conflating "who can configure the gateway" with "who can send traffic through the gateway" is a common shallow mistake.

---

## 10. Multi-Tenancy — How Isolation Actually Works

- **One shared fleet of gateway nodes serves every tenant.** We never spin up a separate gateway instance per customer — that would defeat the purpose and not scale.
- Isolation is achieved through **data namespacing**, not infrastructure separation:
  - Every Redis key is prefixed with `tenantId`.
  - Every Postgres query is scoped by `tenant_id`.
  - Every in-memory config cache entry is keyed by `tenantId`.
- **Demonstration of isolation** (a great demo moment): hammer Tenant B's route until its rate limit trips and its circuit breaker opens, while simultaneously showing Tenant A's requests on the dashboard completely unaffected — different Redis keys, different cached config slice, zero shared mutable state between them.
- The gateway **never trusts a client-supplied tenant ID for anything except routing** — dashboard-side queries always scope by the authenticated user's own `tenant_id` from their session, never from a request parameter.

---

## 11. Leader Election — Why and How

### Why it's needed
Most gateway work (proxying, rate limiting, auth checks) is fully parallelizable — any node can do it independently. But a few jobs must run on **exactly one node at a time**:
- **Active health-checking** of tenant backends — if every gateway node pings every backend on every interval, you multiply health-check traffic against the tenant's own servers for zero benefit.
- **Periodic cleanup/reconciliation** — e.g., expiring stale rate-limit keys, aggregating metrics — duplicate execution wastes work or risks race conditions.

### How it works (v1: Redis lease-based)
```
Every gateway node attempts:
  SET leader:gateway-cluster <node_id> NX EX 10

- If it succeeds: this node is leader for 10 seconds.
- Leader must renew the lease (re-SET with EX) before it expires, roughly every 3-5 seconds.
- If the leader crashes/hangs, the lease naturally expires after 10s,
  and the next node to attempt SET NX succeeds and becomes leader.
```
- The leader runs the health-check loop and any singleton periodic jobs; writes results to Redis (`health:{tenantId}:{backendId}`), which **all** nodes (leader or not) read to make routing decisions.
- **Demo moment**: kill the current leader process live, show a new leader elected within ~1–2 lease intervals, and show health checks never actually stopped happening.
- **Documented tradeoff/limitation** (say this explicitly — it shows maturity): "This lease-based approach can theoretically have a brief window of two 'leaders' during a network partition, up to the lease TTL. A full Raft or Paxos-based approach would give a stronger consistency guarantee via quorum, but was a heavier lift than justified for this job's blast radius (health-check duplication is wasteful, not unsafe)." Optionally, implementing a simplified Raft leader-election (without full log replication) is a stretch goal that would be a very high-signal addition.

---

## 12. Rate Limiting — Algorithm Detail

- **Primary algorithm: Token bucket.**
  - Each `(tenant, apiKey, route)` triple has a bucket with capacity `C` and refill rate `R` tokens/sec.
  - On each request: refill tokens based on elapsed time since `last_refill_ts`, then attempt to deduct 1 token.
  - Enough tokens → allow, deduct, continue. Not enough → `429`.
  - Allows bursts up to bucket capacity while enforcing a long-run average rate — a deliberate, explainable choice.
- **Must be atomic** across concurrent gateway nodes hitting Redis simultaneously — implemented as a single **Redis Lua script** (`EVAL`) so the read-modify-write is not split across round trips (avoiding the classic race where two nodes both read "1 token left" and both allow the request).
- **Alternative mode offered: Sliding window counter** — smoother than fixed windows (no burst-at-boundary problem), cheaper than a full sliding-window log. Exposed as a per-route toggle in the dashboard, giving a genuine algorithmic comparison to discuss.

---

## 13. Load Balancing & Circuit Breaker Detail

### Load balancing strategies
- **Round robin** — simple baseline.
- **Weighted round robin** — respects the `weight` column on `backend_instances`.
- **Least connections** — gateway tracks in-flight request count per backend instance (in Redis or shared in-memory-with-sync) and routes to the least loaded.

### Health checks
- Leader node pings each registered backend's health endpoint (configurable path, default `/health`) every N seconds.
- Unhealthy backends are excluded from the load-balancing rotation until they pass a health check again.

### Circuit breaker (via Resilience4j)
- States: **CLOSED** (normal) → **OPEN** (tripped, requests fail fast without calling the backend) → **HALF_OPEN** (after cooldown, allow a trial request through to test recovery).
- Configurable per backend: failure percentage threshold, minimum request volume before evaluating, cooldown duration.
- Purpose: protect an already-struggling tenant backend from being hammered further, and fail fast for the client instead of hanging.

---

## 14. Distributed Tracing Detail

- Gateway generates (or propagates, if already present) an `X-Trace-Id` header on every request.
- Trace ID flows: client → gateway → tenant's backend → (backend should log/return it, ideally).
- Gateway logs `{trace_id, route, backend, status_code, latency_ms, gateway_node_id, timestamp}` asynchronously to `request_traces` (Postgres) or streams it to the dashboard via WebSocket for live viewing.
- Dashboard renders a **timeline view** per trace ID: when the request hit the gateway, how long the rate-limit/auth checks took, which backend it was routed to, how long the backend took to respond.
- This is a minimal but real version of what tools like Jaeger/Datadog provide — the concept (correlation ID propagation + centralized timeline) is the same, just without a dedicated tracing backend.

---

## 15. Dashboard / UI — What It Controls

The dashboard is not just a viewer — it's how tenants **configure gateway behavior live**, with hot-reload (no gateway restart):

- **Signup/login**, tenant creation.
- **API key management**: generate/revoke keys.
- **Route management**: define path patterns, attach to a backend pool, set rate limit (value + algorithm), toggle auth requirement, toggle tracing.
- **Backend pool management**: add/remove backend URLs, set weights, choose load-balancing strategy, configure circuit breaker thresholds.
- **Live dashboard**: current elected leader node, health status of each backend (green/red), live request rate & latency per route, circuit breaker state per backend, a scrolling/searchable trace timeline.
- **Chaos/demo controls**: buttons to simulate killing a node or spiking traffic against a specific tenant, for demo purposes (should be restricted/sandboxed — e.g., only usable on a demo tenant, not real production tenants).

**Config propagation mechanism**: dashboard writes to Postgres (durable) → publishes a `config:updated` event on Redis pub/sub → every gateway node subscribed to that channel invalidates/refreshes its local in-memory cache for the affected tenant. No gateway restarts required.

---

## 16. Local Development Setup

### Prerequisites
- Docker + Docker Compose
- Java 21+ / Maven or Gradle (for running gateway modules outside Docker if desired)
- Node.js 18+ (for the frontend)

### `docker-compose.yml` services (conceptual — to be fleshed out during implementation)
```yaml
services:
  redis:
    image: redis:7
    ports: ["6379:6379"]

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: gateway_platform
      POSTGRES_PASSWORD: devpassword
    ports: ["5432:5432"]

  admin-api:
    build: ./admin-api          # Spring Boot control-plane service
    environment:
      - REDIS_URL=redis://redis:6379
      - POSTGRES_URL=jdbc:postgresql://postgres:5432/gateway_platform
    ports: ["8080:8080"]
    depends_on: [redis, postgres]

  gateway-node-1:
    build: ./gateway-node        # Spring WebFlux gateway
    environment:
      - NODE_ID=node-1
      - REDIS_URL=redis://redis:6379
      - POSTGRES_URL=jdbc:postgresql://postgres:5432/gateway_platform
    ports: ["9001:9000"]
    depends_on: [redis, postgres]

  gateway-node-2:
    build: ./gateway-node
    environment:
      - NODE_ID=node-2
      - REDIS_URL=redis://redis:6379
      - POSTGRES_URL=jdbc:postgresql://postgres:5432/gateway_platform
    ports: ["9002:9000"]
    depends_on: [redis, postgres]

  dummy-backend-a:
    build: ./test-backends/backend-a
    ports: ["7001:7000"]

  dummy-backend-b-flaky:
    build: ./test-backends/backend-b-flaky   # randomized delay/500s to test circuit breaker
    ports: ["7002:7000"]

  frontend:
    build: ./frontend
    environment:
      - VITE_API_URL=http://localhost:8080
    ports: ["3000:3000"]
    depends_on: [admin-api]
```

### Running locally
```bash
docker-compose up --build
```
- Frontend: `http://localhost:3000`
- Admin API: `http://localhost:8080`
- Gateway nodes: `http://localhost:9001`, `http://localhost:9002` (a local load balancer like nginx can round-robin between them, or the frontend/test client can hit them directly to demonstrate both are equivalent)
- A seed script (`seed.sql` or a Postgres init script) should create one demo tenant with a preconfigured route pointing at `dummy-backend-a` and `dummy-backend-b-flaky`, so the UI isn't empty on first run.

### Local demo script (manual test flow)
1. Open dashboard → log in as seeded demo tenant.
2. Hit the gateway's demo route repeatedly via `curl` or a small load script → watch rate limiting kick in on the live dashboard.
3. Stop `dummy-backend-b-flaky` container → watch health status flip to unhealthy and traffic reroute to `dummy-backend-a` only.
4. Kill `gateway-node-1` container (whichever is leader) → watch leader re-election happen and health checks continue uninterrupted via `gateway-node-2`.

---

## 17. Deployment (Free-Tier Plan)

| Component | Where | Notes |
|---|---|---|
| Gateway nodes (2, later N) | **Oracle Cloud Free Tier VM** (Ampere ARM, 4 OCPU/24GB free forever) running as separate Docker containers | Genuinely separate processes for real failure testing |
| Redis | **Upstash** free tier | Managed, serverless, survives gateway node restarts |
| Postgres | **Supabase** or **Neon** free tier | Managed Postgres, includes optional managed auth (Supabase) |
| Dummy/demo backends | Same Oracle VM, or split one onto **Fly.io** free tier for genuine cross-host latency | Optional but adds realism |
| Admin API | Same Oracle VM (or a small separate container) | Could co-locate with gateway nodes initially |
| Frontend | **Vercel** free tier | Auto-deploy from GitHub |
| Load balancer in front of gateway nodes | Simple **nginx** container on the Oracle VM, or Oracle's free Load Balancer (limited free allocation) | Round-robins between gateway-node-1 and gateway-node-2 |
| Domain (optional) | Free subdomain via Vercel, or a cheap/free DNS provider | Nice-to-have for a polished public demo link |

**Total cost: $0/month.**

### Scaling later (explicitly designed for, no rewrite needed)
Because every gateway node is stateless (all shared state lives in Redis/Postgres, nothing tenant-specific in local memory beyond a disposable cache), going from 2 nodes to N nodes later is purely an infrastructure change:
```
docker-compose up --scale gateway-node=10
```
or equivalent in a container orchestrator (Kubernetes Deployment replica count) — **zero application code changes required.** This should be stated explicitly in the README/resume: *"Designed to scale horizontally; current free-tier deployment runs 2 nodes, architecture supports N nodes with no code changes."*

---

## 18. Making It "Ready to Use" (Product Polish Checklist)

- [ ] One-command local setup (`docker-compose up`) with a seeded demo tenant.
- [ ] Public hosted demo URL where a stranger can sign up, register a backend, and route real traffic — no install required.
- [ ] A "reset my demo config" button (since it's public) to avoid stale/broken state for visitors.
- [ ] README with: architecture diagram, 2-minute setup walkthrough, and ideally a short GIF/video of the live dashboard reacting to a killed node.
- [ ] Minimal integration docs page: "how to point your app at this gateway" with a copy-pasteable curl example.
- [ ] Clear statement in README of what's implemented vs. roadmap (honesty about scope is a positive signal, not a negative one).

---

## 19. Suggested Build Order (Always Have Something Demoable)

1. **Milestone 1 — Bare proxy.** Single gateway node forwards requests to one hardcoded backend. No Redis/Postgres yet.
2. **Milestone 2 — Multi-tenant routing.** Add Postgres, tenant/route/backend-pool models, path-based tenant extraction, admin API for CRUD.
3. **Milestone 3 — Rate limiting.** Add Redis, implement token bucket via Lua script, wire into gateway request path, surface live counts on dashboard.
4. **Milestone 4 — Load balancing + health checks.** Multiple backend instances per pool, round robin, active health checks (single node only for now).
5. **Milestone 5 — Leader election.** Add second gateway node, implement Redis lease-based election, move health-checking to be leader-only, demo failover.
6. **Milestone 6 — Circuit breaker.** Integrate Resilience4j per backend instance, wire trip/reset logic to real failure responses from the flaky dummy backend.
7. **Milestone 7 — Distributed tracing.** Trace ID generation/propagation, async logging to `request_traces`, timeline view in dashboard.
8. **Milestone 8 — Auth (both layers).** Dashboard login (product auth), API-key issuance and edge validation, optional JWT validation per route.
9. **Milestone 9 — Polish for demoability.** Chaos buttons, live WebSocket dashboard, seed data, README, hosted deployment.
10. **Stretch goals (only after 1–9 are solid):** simplified Raft leader election, request/response transformation, canary routing, Prometheus/Grafana integration.

---

## 20. Resume / Interview Framing

**Resume bullet (draft):**
> Built a multi-tenant, horizontally-scalable API gateway platform (Java/Spring WebFlux, Redis, Postgres) providing distributed rate limiting (atomic Lua-script token buckets), load balancing with active health checks, per-backend circuit breaking (Resilience4j), Redis lease-based leader election for coordinated background jobs, and end-to-end distributed tracing — designed with fully stateless gateway nodes so throughput scales horizontally with zero code changes.

**Be ready to discuss in depth:**
- Why atomicity matters for the rate limiter (race condition without it) and how the Lua script prevents it.
- Leader election tradeoffs: lease-based (simple, brief dual-leader risk on partition) vs. Raft/Paxos (stronger guarantee, more complex) — and why the chosen approach was acceptable for this job's blast radius.
- How multi-tenancy isolation is enforced (data namespacing, never trusting client-supplied tenant IDs for anything but routing).
- The difference between product auth and edge auth, and why conflating them is a design smell.
- What would need to change to run this at real scale (e.g., moving from Redis pub/sub to Kafka for stronger delivery guarantees, sharding Redis itself, moving `request_traces` to a proper time-series store).
- Honest limitations: this is not a full Raft implementation; health-check leader election has a small dual-leader window; tracing is minimal compared to Jaeger/Datadog. Stating these unprompted is a strength, not a weakness.

---

## 21. Summary for Any AI Assistant Reading This Doc

If you (an AI model) are given this document with a follow-up request, assume the following context is already true unless the user says otherwise:
- The project is a **multi-tenant API gateway platform**, not a hosting/PaaS platform — we never run the tenant's backend, only proxy to URLs they provide.
- Tech stack is locked: **Java/Spring Boot (WebFlux) + Resilience4j** for the gateway, **TypeScript/React** for the dashboard, **Postgres** for durable data, **Redis** for ephemeral/coordination data.
- The 8 core features in Section 4 are the scope; anything else is explicitly "future work" unless the user asks to expand scope.
- All gateway nodes must remain stateless; any new feature design must be checked against this constraint.
- Deployment target is **free-tier only** (Oracle Cloud free VM, Upstash, Supabase/Neon, Vercel) with an explicit, already-agreed story for scaling later without code changes.
- The end goal is a genuinely usable, multi-tenant, demoable product — not a single-user script — intended primarily as a high-signal portfolio project for backend/distributed-systems roles at companies like Amazon and Uber.
