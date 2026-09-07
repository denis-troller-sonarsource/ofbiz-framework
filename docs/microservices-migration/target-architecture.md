# Target Architecture: OFBiz Microservices Decomposition

## Status

Proposal. Companion document: [`migration-plan.md`](./migration-plan.md) describes how to get here incrementally via the strangler fig pattern.

## 1. Why, and what we're starting from

This repository is stock Apache OFBiz trunk (single Gradle multi-project build, single JVM, single embedded Tomcat). The as-built architecture was surveyed directly rather than assumed from OFBiz documentation. The findings that drive every decision below:

- **One process, one deployable.** `framework/catalina` embeds Tomcat inside the OFBiz JVM (`Start.java` → `ContainerLoader` → `CatalinaContainer`). All ~20 webapp contexts (`/ordermgr`, `/partymgr`, `/catalog`, `/accounting`, `/ar`, `/ap`, `/content`, `/humanres`, `/manufacturing`, `/marketing`, `/sfa`, `/workeffort`, `/webtools`, `/rest`, ...) are mounted in that same JVM. `./gradlew distTar` produces one tarball; Docker runs one process. Separate URL mount points give the *appearance* of modularity but not process isolation.
- **One shared relational schema.** `applications/datamodel` centrally defines ~893 entities for every domain (order, party, product, accounting, content, marketing, workeffort, humanres, manufacturing, shipment). `entityengine.xml` routes essentially all of them to a single datasource. There is no per-domain schema separation today.
- **Deep, direct foreign-key coupling.** `Party` is referenced by 200 `<relation>` elements across every other domain's entity model — a true shared kernel. `Product` (73), `StatusItem` (87), `Uom` (86), `GlAccount` (38), `ContactMech`/`PostalAddress` (76), `Content`/`DataResource` (53), and `WorkEffort` (48) are secondary shared kernels referenced across domain boundaries.
- **In-process synchronous service calls, not network calls.** `order` code calls `dispatcher.runSync("createPayment"/"createInvoiceForOrder"/"refundPayment", ...)` (accounting), `getPartyAccountingPreferences` (party), and `calculateProductPrice`/`getAllProductVariants` (product) — all same-JVM Java method dispatch via the shared `LocalDispatcher`. There is no network boundary, no serialization, and no independent failure domain between these "components" today.
- **Latent, unused decoupling infrastructure.** The service engine already has JMS (`JmsServiceEngine`) and RMI (`RmiServiceEngine`, live at `rmi://localhost:1099/RMIDispatcher`) engines. JMS is fully implemented but commented out of `serviceengine.xml`. This means OFBiz's own service contracts (`servicedef/services.xml`) are already transport-agnostic — a service's *interface* doesn't need to change when its *implementation* moves to another process.
- **A ready-made external facade seam.** `framework/rest-api` already projects declared services over HTTP/JSON with Swagger docs. It is the natural ancestor of the API Gateway described below.

Given this starting point, the target architecture below is deliberately conservative about *what* changes (bounded contexts, data ownership, communication style) and explicit about accepting a transition period where legacy monolith and new services coexist — because in a strangler-fig migration, that transition period is most of the actual work.

## 2. Target architecture overview

```
                                   ┌─────────────────────┐
                                   │      API Gateway      │
                                   │ (routing, authN/Z,    │
                                   │  rate limiting, TLS)  │
                                   └──────────┬───────────┘
                     ┌────────────────────────┼─────────────────────────────┐
                     │                        │                             │
             ┌───────▼────────┐      ┌────────▼────────┐           ┌────────▼────────┐
             │  OFBiz Monolith │      │  Extracted        │  ...    │  Extracted        │
             │  (shrinking)    │      │  Microservice A   │         │  Microservice N   │
             │                 │      │  (e.g. Content)   │         │  (e.g. Order)     │
             └───────┬────────┘      └────────┬────────┘           └────────┬────────┘
                     │                        │                             │
                     │ legacy shared DB       │ owns its own schema/DB      │ owns its own schema/DB
                     ▼                        ▼                             ▼
             ┌─────────────────┐     ┌─────────────────┐          ┌─────────────────┐
             │  Legacy schema   │     │  Service A DB    │          │  Service N DB    │
             │  (shrinking)     │     │                  │          │                  │
             └─────────────────┘     └─────────────────┘          └─────────────────┘

                            ┌───────────────────────────────┐
                            │        Event Backbone          │
                            │   (Kafka/RabbitMQ — domain      │
                            │    events, CDC from legacy DB)  │
                            └───────────────────────────────┘
                     ▲               ▲                ▲                ▲
                     │ publishes     │ publishes       │ publishes      │ subscribes
             Monolith (via CDC   Service A        Service N       Any service needing
             or dual-write)                                        another's reference data
```

Two structural elements carry the whole migration and are described first: the **API Gateway** and the **Event Backbone**. Everything else — which services exist, in what order — is layered on top of them.

## 3. API Gateway (the strangler-fig facade)

A dedicated API Gateway (e.g. Kong, Envoy/Envoy Gateway, or Spring Cloud Gateway — final pick is an infra decision, not an architectural one) sits in front of **all** traffic, monolith and extracted services alike, from day one of the migration — before a single service is extracted.

Responsibilities:
- **Routing.** Path/host-based rules decide whether a request goes to the monolith or to an extracted service. Extracting a capability becomes "add a route + stand up the service," not "rewrite how clients reach it."
- **AuthN/Z.** Terminate authentication centrally (see §6) so extracted services don't reimplement OFBiz's `LoginWorker`/session model — they trust a token the gateway validated.
- **Cross-cutting concerns.** Rate limiting, TLS termination, request/response logging, correlation-ID injection for distributed tracing.
- **Contract stability.** External API consumers (storefront, mobile, partner integrations) see one stable surface regardless of what's monolith vs. extracted behind it.

The gateway is introduced immediately (see migration plan phase 0) and never removed — it is permanent target-state infrastructure, not scaffolding. This directly reflects the survey finding that `framework/rest-api` already declares stable service contracts (`servicedef/services.xml`) decoupled from transport; the gateway is what makes that contract externally addressable and swappable behind the scenes.

## 4. Communication patterns

Two patterns, chosen deliberately for different purposes — this is not "REST vs. events," it's "which one for which kind of interaction":

### 4.1 Synchronous REST/JSON — for request/response
Used when a caller needs an answer before it can proceed: a query, or a command whose success/failure the caller must know immediately (e.g. "place this order," "authorize this payment"). Each extracted service exposes a versioned REST API (OpenAPI-documented, building on the existing `framework/rest-api` Swagger tooling and `servicedef/services.xml` service contracts as the source of interface truth during extraction).

Rules of engagement:
- Services call each other over HTTP, never by reaching into another service's database.
- Timeouts, retries with backoff, and circuit breakers (e.g. via the gateway or a lightweight client library) are mandatory for every synchronous cross-service call — this replaces the free, unbounded in-process call that `order` makes into `accounting`/`product` today. That free lunch is over; the migration plan's per-service checklist accounts for this.
- Synchronous call graphs must stay shallow. A service calling a service that calls a service recreates the monolith's coupling with worse latency and worse failure modes. If a chain gets deep, that's a signal the boundary is wrong.

### 4.2 Asynchronous events — for state propagation
Used when other services need to *know something happened*, not approve it. `OrderPlaced`, `PaymentCaptured`, `InventoryAdjusted`, `PartyUpdated` are events, not RPCs — consumers react on their own time.

- A message broker (Kafka preferred for retention/replay if event sourcing or read-model rebuilding matters; RabbitMQ acceptable if simpler pub/sub suffices) replaces the commented-out, dormant JMS engine as the real backbone.
- Each extracted service publishes domain events for state changes other bounded contexts plausibly care about, and owns its event schema (versioned, backward-compatible additions preferred over breaking changes).
- While a capability still lives in the monolith, the *monolith* is the event publisher (see Change Data Capture note in §5.3) — services being extracted later can start consuming real events before their own extraction happens, decoupling their build-out from the monolith's release cycle.

### 4.3 What NOT to do
- No direct database access across service boundaries, ever — including "just this one read-only view," which is exactly how the current 200-reference `Party` coupling happened.
- No distributed transactions/2PC across services. OFBiz currently gets this for free via Geronimo JTA inside one JVM; microservices give it up. Cross-service consistency is handled via the Saga pattern (orchestrated for complex multi-step business processes like order fulfillment, choreographed via events for simpler cases) with compensating actions, not distributed commit.

## 5. Data ownership

### 5.1 Database-per-service
Each extracted microservice owns its own schema (starting point: schema-per-service on the *existing* database engine to reduce infrastructure lift; graduating to physically separate databases per service as they mature and their operational needs diverge). No other service, and critically no lingering monolith code, may query another service's schema directly. This is the hard rule that actually delivers independent deployability — it's also the one the current codebase violates most flagrantly (428 relations in `order-entitymodel.xml` alone), so it's where the discipline will be tested hardest.

### 5.2 Shared-kernel entities: Party, Product, StatusItem, Uom
These are referenced everywhere (§1) precisely because OFBiz treats them as shared vocabulary, not because every domain has an equal claim to owning them. The target model:

- **`Party` becomes the responsibility of a Party/Identity service.** It is the most-referenced entity in the system and the textbook shared kernel; giving it a dedicated owning service (rather than folding it into, say, the CRM/marketing service) avoids creating a new hidden monolith-within-a-monolith.
- **`Product` becomes the responsibility of a Catalog/Product service.**
- **`StatusItem`, `Uom`, `RoleType`, `Enumeration`, `Geo`** (the small, rarely-changing "shared vocabulary" tables) are treated as **reference data**, owned by whichever service is most authoritative for each (e.g. `Uom` under Product, `RoleType`/`Enumeration` under Party), and are **not** queried live, cross-service, per request.
- **Every other service keeps a local, denormalized, read-only cache/replica of the shared-kernel data it needs**, kept current via the event backbone (e.g. Order's local `party_summary` table is updated by consuming `PartyUpdated` events). This trades strict consistency for availability and service autonomy — deliberately. A service placing an order needs to *know* a party exists and its key attributes; it does not need to be the source of truth for that party, and it must not block on a synchronous call to Party for every order line just to resolve a name.
- Synchronous fallback: for the small number of cases where an eventually-consistent local cache is not acceptable (e.g. authorizing a large order against live credit terms), the calling service makes a synchronous REST call to the owning service rather than a database join. This is the exception, not the default — the default is the local cache.

This directly answers the survey's central risk: extracting `order` or `accounting` without a plan for `Party`/`Product` just recreates tight coupling over a network call instead of a function call. The local-cache-plus-events model is what actually breaks that coupling.

### 5.3 Getting data out of the shared schema without a big-bang cutover
Change Data Capture (CDC, e.g. Debezium against the legacy database's write-ahead log) streams changes from tables the monolith still owns into the event backbone. This lets:
- Newly extracted services consume real production data as events immediately, before the owning tables are physically migrated.
- The strangler-fig monolith and new services stay consistent during the (potentially long) coexistence period without hand-rolled dual-write code sprinkled through legacy Java.

Dual writes (application-level, writing to both old and new stores during a single extraction's transition window) are the fallback where CDC isn't practical (e.g. no WAL access on the target datasource), but are scoped tightly and temporary by design — they're a known source of drift bugs and should not become a long-term pattern.

## 6. Cross-cutting concerns

### 6.1 Authentication & authorization
OFBiz's `LoginWorker` (session-based) coexists today with `JWTManager`/`TokenFilter` (already present in `framework/webapp`) — the codebase has already started down the token path. Target state: the API Gateway issues/validates JWTs (or validates tokens from a dedicated identity provider, e.g. Keycloak, fronting the Party/Identity service for user records) and passes a verified identity + claims downstream to every service. Extracted services never implement their own session/login logic; they trust the gateway-verified token. `externalLoginKey`-style cross-context SSO (recently hardened in this codebase per commit `45e8de777f`) becomes unnecessary once there's one real token-based identity boundary instead of ad hoc cross-webapp key propagation.

### 6.2 Observability
Distributed tracing (OpenTelemetry, correlation ID injected at the gateway and propagated through both sync HTTP calls and async event headers) is non-negotiable once a request can span multiple processes — today a stack trace tells the whole story; post-migration it won't. Centralized structured logging and per-service metrics (RED: rate, errors, duration) round this out. This is infrastructure that must exist *before* the first service extraction, not added after problems appear.

### 6.3 Resilience
Timeouts, retries, circuit breakers (§4.1) at every synchronous call. Bulkheading (separate connection pools/thread budgets per downstream dependency) so one slow extracted service can't exhaust a caller's resources the way an in-process slow method never could before but a network call absolutely can.

### 6.4 Transactions and consistency
As noted in §4.3: no distributed 2PC. Business processes that span services (e.g. "place order" touching Order, Product/Inventory, Party, Accounting) become explicit Sagas with compensating actions (e.g. `CancelReservation` compensates `ReserveInventory`). This needs to be designed per business process, not assumed away — it is the single biggest behavioral change from how OFBiz works today (free JTA/XA transactions across all "components" in one JVM).

## 7. Target service boundaries

Derived from the survey's coupling analysis, not from OFBiz's existing component names by default — a couple of stock components are split or merged where the entity/service coupling data suggested a better seam. Extraction order (least-coupled first) is covered in the migration plan; this is the destination, not the sequence.

| Target service | Derived from stock component(s) | Owns (data) | Notes |
|---|---|---|---|
| **Content/CMS Service** | `content` | `Content`, `DataResource`, related entities | High fan-in (everything attaches content/images to it) but low fan-out (only ~7 outgoing Party references) — behaves like infrastructure, not a peer domain. Good early candidate. |
| **Manufacturing Service** | `manufacturing` | BOM, production runs, routings | Smallest entity footprint (9 entities), fewest cross-domain relations. Best first real domain extraction. |
| **HR Service** | `humanres` | Employees, positions, leave, performance | Moderate `Party` coupling (22 refs) but otherwise self-contained. |
| **Marketing/CRM Service** | `marketing` | Campaigns, contact lists, SFA, opportunities | Moderate coupling; benefits from Party service existing first. |
| **Catalog/Product Service** | `product` | `Product`, `ProductCategory`, pricing, promotions, facility/inventory | Second shared-kernel service; needed before Order can be extracted cleanly. |
| **Party/Identity Service** | `party` | `Party`, `PartyRole`, `Person`, `PartyGroup`, `ContactMech`, `UserLogin` | The shared kernel. Highest-risk, highest-blast-radius extraction; extracted after the org has practiced the pattern on lower-stakes services. |
| **Accounting/Finance Service** | `accounting` | GL, AR, AP, invoices, payments | Deeply coupled to Order and Party; extracted alongside or just after them with a strong anti-corruption layer. |
| **Order Service** | `order` | Orders, quotes, requirements, returns | Most entangled component in the system (428 relations, direct synchronous calls into accounting/product/party). Last major extraction. |
| **Work Effort Service** | `workeffort` | Tasks, calendar, activities | Consumed by manufacturing, HR, CRM — extract once its consumers are already service-oriented. |

Reference-data ownership (`StatusItem`, `Uom`, `RoleType`, `Enumeration`, `Geo`) is split across Party and Catalog/Product as noted in §5.2 rather than given its own service — a dedicated "reference data service" was considered and rejected: it would become a new synchronous bottleneck every other service depends on for basic lookups, trading one shared kernel for another without reducing coupling.

## 8. What stays out of scope for now

- `framework/security`, `framework/entity`, `framework/service`, `framework/webapp` etc. are platform/library code, not services — they remain part of whatever OFBiz footprint still exists (shrinking monolith) and are not "extracted."
- `webtools` (admin/debug UI) and `securityext`/`commonext` (extension points) are operational tooling, not business domains — no plan to extract them as services.
- Multi-tenancy (`entitygroup.xml` tenant routing) is out of scope for this migration; it's an orthogonal concern that can be revisited once the service boundaries above exist.

## 9. Success criteria for the target state

- No service reads another service's database directly.
- Every cross-service synchronous call has a timeout, retry policy, and circuit breaker.
- The API Gateway is the only public entry point; no service is directly internet-addressable.
- Every domain event has a versioned, documented schema.
- A single service can be deployed, scaled, and rolled back independently of every other service and of the remaining monolith.
