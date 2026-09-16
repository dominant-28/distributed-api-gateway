# Distributed API Gateway Platform

A multi-tenant, distributed API gateway with rate limiting, load balancing,
circuit breaking, leader election, and distributed tracing.

## Status: 🚧 In active development

## Structure
- `gateway-node/` — Spring WebFlux proxy service
- `admin-api/` — Spring Boot control-plane API
- `frontend/` — React + TypeScript dashboard
- `test-backends/` — dummy backends used for local testing/demos
- `docs/` — full design document

See `docs/project-design-doc.md` for full architecture, data models, and build plan.