# API Gateway

Strangler-fig API Gateway fronting the OFBiz monolith. See
[`docs/microservices-migration/migration-plan.md`](../docs/microservices-migration/migration-plan.md)
(Phase 0, item 1) and
[`docs/microservices-migration/target-architecture.md`](../docs/microservices-migration/target-architecture.md)
(§3) for the design this implements.

## What this is (and isn't) yet

This is the first, deliberately minimal slice: a reverse proxy that forwards
100% of requests to the OFBiz monolith unchanged, plus its own `/health`
endpoint. There is no routing logic, no auth, no rate limiting, and no
extracted services to route to yet — those land in later Phase 0 items and
the extractions in migration-plan.md §5. The point of this step is to prove
the gateway itself works before anything depends on it.

## Running locally

```sh
cd gateway
npm install
MONOLITH_TARGET_URL=http://localhost:8443 PORT=3000 npm start
```

- `PORT` — port the gateway listens on (default `3000`).
- `MONOLITH_TARGET_URL` — base URL of the OFBiz monolith to proxy to
  (default `https://localhost:8443`, OFBiz's default HTTPS port).

`GET /health` returns `200 {"status":"ok"}` without touching the monolith.
Every other request is forwarded as-is.

## Testing

```sh
npm test
```

Tests use `supertest` (in-process requests against the Express app) and
`nock` (stubs the monolith HTTP target), so no running OFBiz instance is
required. Coverage is enforced at 80% (branches/functions/lines/statements)
via the `jest.coverageThreshold` config in `package.json`.
