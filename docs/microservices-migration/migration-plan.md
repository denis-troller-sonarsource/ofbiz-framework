# Migration Plan: Strangler-Fig Extraction from the OFBiz Monolith

## Status

Proposal, implementable incrementally. Companion document: [`target-architecture.md`](./target-architecture.md) describes the destination this plan walks toward.

## 1. Principles

- **Strangler fig, not big bang.** The monolith keeps running throughout. Each extraction redirects one slice of traffic/data ownership to a new service and leaves everything else untouched. At every point in this plan, the system is in a shippable, fully-functional state — there is no "everything is half-migrated and nothing works" phase.
- **One extraction, one feature branch, one merge.** Each numbered extraction in §5 is scoped to land as its own feature branch against `trunk`, reviewed and merged independently. Extractions are not batched — if a branch grows to cover more than one extraction, split it.
- **Facade before flesh.** For every extraction, the API Gateway route and the anti-corruption layer (ACL) are stood up *before* the underlying implementation moves. This means a rollback is always "flip the route back," never "restore a database from backup."
- **Data follows a fixed sequence, not ad hoc judgment calls.** Every extraction follows the same five-step data-migration sequence (§4) regardless of which domain it is. This is what makes the plan repeatable across ~9 extractions instead of bespoke each time.
- **Reversibility at every step.** Every phase below has an explicit rollback path. If a phase can't be cleanly rolled back, it's broken into smaller phases until it can.

## 2. Preconditions (Phase 0 — do this once, before any domain extraction)

This is infrastructure work, not a domain extraction, but it's a hard prerequisite for everything in §5. Track it as its own feature branch(es).

1. **Stand up the API Gateway** (target-architecture.md §3) in front of the existing monolith, initially just proxying 100% of traffic through unchanged. This is deliberately a no-op from the user's perspective — it validates the gateway works before it's load-bearing for any extraction.
2. **Stand up the event backbone** (Kafka or RabbitMQ). No services depend on it yet.
3. **Stand up CDC** (e.g. Debezium) against the legacy database, initially capturing changes with no consumers — this proves the pipe works before any service trusts it.
4. **Stand up centralized observability**: distributed tracing (OpenTelemetry) with correlation-ID propagation through the gateway, centralized logging, per-service metrics dashboards. Do this before extraction #1, not after the first incident.
5. **Stand up the target auth model**: gateway-level JWT issuance/validation, coexisting with (not yet replacing) `LoginWorker`. Confirm both paths work side by side.
6. **Establish the per-extraction playbook and checklist** (§3) with the team, and pick tooling for schema-per-service (e.g. a new schema in the existing PostgreSQL/MySQL instance is enough to start — physically separate database instances per service can come later).

Exit criteria for Phase 0: gateway is in the request path for 100% of production traffic with zero behavior change, CDC is streaming to the event backbone, tracing shows up end-to-end for a sample request, and the team has run through the checklist once on a throwaway/dummy extraction.

## 3. The per-extraction playbook

Every extraction in §5 — regardless of which domain — follows these steps, each typically its own commit or small set of commits within the extraction's feature branch:

1. **Define the service contract first.** Start from the domain's existing `servicedef/services.xml` — those service signatures are already a decoupled contract (this was a deliberate finding from the architecture survey: OFBiz's service definitions are transport-agnostic today). Write the new service's OpenAPI spec to match, adjusting only where the old contract leaked implementation details (e.g. raw entity field names that shouldn't be public API).
2. **Stand up the new service skeleton**, empty schema, CI/CD pipeline, health checks, wired into observability from day one. No business logic yet.
3. **Create the anti-corruption layer (ACL).** Inside the monolith, introduce a facade interface for the capability being extracted (e.g. `PartyGateway` for the Party extraction) that all monolith call sites are refactored to use *instead of* calling `dispatcher.runSync(...)` or the `Delegator` directly. Initially, this facade's implementation just delegates to the existing in-process code — behaviorally a no-op, but it collapses every call site down to one seam.
4. **Migrate data (see §4).**
5. **Implement the new service's business logic**, ported from the monolith's Java/Groovy/service-def implementations, tested against the new schema.
6. **Point the ACL at the new service** instead of the in-process implementation, behind a feature flag. The ACL now makes a REST call (or publishes/consumes an event) instead of an in-process call.
7. **Add the gateway route** for any capability that external clients call directly, and switch it from "route to monolith" to "route to new service" behind the same flag.
8. **Canary the flag**: a small percentage of traffic, then increasing, while comparing behavior/error rates/latency against the old path (shadow traffic or dark-launch comparison where feasible).
9. **Flip 100%, hold, then remove the old code path** from the monolith — the dead `runSync` targets, the now-unused entity defs, the old webapp controller routes that the gateway no longer sends traffic to. This last step is not optional and not deferrable to "later" — a strangler fig that never removes the strangled branch isn't a migration, it's a second system to maintain.
10. **Update this plan's status table (§6)** and capture what was harder/easier than expected — each extraction should make the next one faster.

### Rollback path at each step
- Steps 1–5: purely additive, nothing in production depends on them yet — rollback is deleting the branch.
- Step 6–8 (flag-guarded cutover): rollback is flipping the flag back to the in-process/monolith path. This must be a config change, not a deploy — that's the entire point of putting the ACL and flag in place before touching data ownership.
- Step 9 (removing old code): only done after the flag has been at 100% for an agreed soak period (proposed: 2 weeks of normal business cycles, longer for Order/Accounting/Party). This is the one irreversible step per extraction — treat it accordingly.

## 4. The five-step data migration sequence

Applied within step 4 of every extraction:

1. **Identify the entity set** the domain owns exclusively vs. entities it merely references (shared-kernel entities like `Party`, `Product`, `StatusItem`, `Uom` — see target-architecture.md §5.2). Only the exclusively-owned entities move; shared-kernel references become either a local cache (populated via CDC/events) or a synchronous call to the (eventually) owning service.
2. **Stand up the new schema** for the owned entities in the new service's database, translated from the existing `entitymodel.xml` definitions (field types, not necessarily the same physical column layout — this is a chance to drop OFBiz-generic modeling like generic `StatusItem` FKs in favor of an explicit enum if that fits the new service better, but that's an optional cleanup, not a requirement).
3. **Backfill** the new schema from the legacy database (one-time bulk copy).
4. **Dual-write via CDC**, not application code: the legacy tables continue to be written by the monolith exactly as today; CDC streams those changes into the event backbone; the new service consumes and applies them to its own schema. This keeps the new schema current during the transition without touching monolith write paths at all — directly avoiding hand-rolled dual-write bugs.
5. **Cut over writes**: once the new service is serving reads correctly and its data has been validated against the legacy source (row-count and spot-check reconciliation, run for at least a few days), the new service becomes the write owner. The monolith's ACL (step 6 of §3) now writes through the new service's API instead of the legacy table, and CDC either stops or is inverted (new service → legacy table, only if something in the still-unmigrated monolith still needs to read that data directly — a temporary compatibility shim, removed as soon as possible).

## 5. Extraction sequence

Ordered least-coupled-first, per the architecture survey's relation-count analysis. Rationale: build organizational muscle memory and tooling maturity on low-blast-radius domains before touching the shared kernel (`Party`) and the most entangled transactional triad (`Order`/`Accounting`/`Product`). Each row is one feature branch (or a short sequence of branches if the team prefers finer-grained review, but never coarser than one row).

| # | Extraction | From component(s) | Why here in the sequence | Key risks / watch-outs |
|---|---|---|---|---|
| 1 | **Content/CMS Service** | `content` | Lowest fan-out (7 outgoing Party references), high fan-in but as a passive dependency (things attach to it, it doesn't reach into them). Ideal first real extraction to prove the playbook end-to-end. | Every other domain will keep calling this service once extracted — make sure its API is stable before extraction #2 starts depending on it. |
| 2 | **Manufacturing Service** | `manufacturing` | Smallest entity footprint (9 entities) of any domain component. Minimal Party coupling. | Depends on Product (BOM references products) — either accept a synchronous call to the still-in-monolith Product code via the ACL, or build the local-cache pattern early here as a dry run for when Product itself is extracted. |
| 3 | **HR Service** | `humanres` | Self-contained domain graph beyond the mandatory Party link (22 references). Not on the critical path of customer-facing flows, so mistakes here are lower-stakes. | Party dependency (employee-as-party) is the first real test of the local-cache-via-events pattern from target-architecture.md §5.2 — treat this as the template the later, higher-stakes extractions will copy. |
| 4 | **Marketing/CRM Service** | `marketing` | Moderate coupling, benefits from having a working Party-reference pattern already proven in HR. | Contact lists / SFA data overlaps conceptually with Party's `ContactMech` — resist the temptation to let this service become a second home for party data; it should reference, not duplicate ownership of, party identity. |
| 5 | **Work Effort Service** | `workeffort` | Consumed by manufacturing, HR, and (later) CRM/order — extracting it after its current consumers are already service-oriented means those consumers integrate with it over the network from day one instead of needing a second migration later. | Calendar/iCal webapp (`/iCalendar`) is a direct external integration point (email/calendar clients) — verify nothing outside OFBiz depends on its current URL structure before moving it behind the gateway. |
| 6 | **Catalog/Product Service** | `product` | Second shared-kernel extraction. Needed before Order can be touched, since Order's pricing/inventory calls (`calculateProductPrice`, `getAllProductVariants`) are direct synchronous dependencies today. | High read volume from storefront-type consumers — this is where the local-cache-vs-synchronous-call tradeoff (target-architecture.md §5.2) needs the most careful load testing before cutover. |
| 7 | **Party/Identity Service** | `party` | The shared kernel (200 relation references). Deliberately not first or last: the team has by now run the local-cache/event pattern four times (steps 2–5) and refined it; Party is the highest-blast-radius entity, so it goes after the pattern is proven, but before Order/Accounting need it as a finished dependency. | This is the extraction most likely to surface hidden direct-`Delegator` reads (bypassing services entirely) that steps 3 of the playbook (ACL introduction) needs to catch. Budget extra time for discovering these call sites; grep for `Delegator`/`EntityQuery` usage against `Party*` entities across every component before starting, not during. |
| 8 | **Accounting/Finance Service** | `accounting` | Deeply coupled to both Order and Party; only tractable once Party is out. | Financial data has the strictest consistency/audit requirements in the system — the Saga/compensating-action design (target-architecture.md §4.3/§6.4) needs the most rigor here. Get sign-off from whoever owns financial-controls/audit requirements before cutover, not after. |
| 9 | **Order Service** | `order` | Most entangled component in the codebase (428 relations, direct synchronous calls into accounting/product/party found in `OrderReturnServices.java`/`OrderChangeHelper.java`). Last, and by far the largest single extraction. | Consider splitting this extraction further (e.g. Quote/Requirement vs. Order-proper vs. Returns) rather than doing it as one branch — by this point in the migration the team will have enough practice to judge whether that's warranted. This is also where the monolith effectively stops being a monolith — plan a project milestone/retrospective here. |

Reference-data ownership (`StatusItem`, `Uom`, `RoleType`, `Enumeration`, `Geo`) is not a separate row — each piece moves with whichever service in the table above becomes its owner (per target-architecture.md §5.2 and §7), at that service's extraction step.

## 6. Tracking status

Maintain this table as extractions land — update it as part of step 10 of the playbook (§3), not separately:

| # | Extraction | Status | Branch | Notes |
|---|---|---|---|---|
| 0 | Gateway / event backbone / CDC / observability / auth (Phase 0) | In progress | `feat-bare-api-gateway` | Item 1 (no-op API Gateway proxy, `gateway/`) underway; items 2–6 (event backbone, CDC, observability, auth, playbook) not started |
| 1 | Content/CMS Service | Not started | — | |
| 2 | Manufacturing Service | Not started | — | |
| 3 | HR Service | Not started | — | |
| 4 | Marketing/CRM Service | Not started | — | |
| 5 | Work Effort Service | Not started | — | |
| 6 | Catalog/Product Service | Not started | — | |
| 7 | Party/Identity Service | Not started | — | |
| 8 | Accounting/Finance Service | Not started | — | |
| 9 | Order Service | Not started | — | |

## 7. Definition of done for the migration

- All nine domain extractions in §5 have completed step 9 of the playbook (old code path removed from the monolith).
- The only code remaining in `applications/` is whatever genuinely has no independent business-domain identity (if anything) — otherwise `applications/` and `applications/datamodel` are retired.
- `framework/*` platform code either: (a) has been extracted into shared libraries consumed by the new services, or (b) is retired if superseded by equivalent off-the-shelf infrastructure (e.g. the gateway replacing `framework/webapp`'s routing role, the event backbone replacing `JobManager`/dormant JMS).
- The API Gateway routes 100% of traffic to independently deployable services; there is no remaining OFBiz monolith process.
- Every success criterion in target-architecture.md §9 holds in production, not just in a subset of services.

## 8. Explicit non-goals for this plan

- This plan does not cover a UI/frontend rewrite. Existing OFBiz screens/widgets can continue to render against extracted services through the ACL/gateway for as long as needed; frontend modernization is a separate initiative.
- This plan does not mandate a specific cloud provider, container orchestrator, or gateway/broker product — those are infrastructure decisions to be made when Phase 0 starts, informed by the operating team's existing expertise.
- This plan does not attempt to extract `plugins/` content, since no custom plugin code exists in this checkout (confirmed during the architecture survey — the plugins directory is empty and, when pulled, points at the stock `apache/ofbiz-plugins` repository). If a production deployment layers custom plugins on top of this framework, they should be surveyed and slotted into §5 relative to their actual coupling before that deployment adopts this plan.
