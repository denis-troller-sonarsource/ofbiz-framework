# OFBiz-to-Azure microservices modernization plan

## 1. Purpose

This document is an execution plan for incrementally replacing the OFBiz modular monolith with a modern, independently deployable, Azure-hosted system. It is written so that engineering teams or AI coding agents can convert each work package into repository changes, tests, infrastructure, and deployment evidence.

The plan deliberately avoids a big-bang rewrite. OFBiz contains broad coupling through its Entity Engine, Service Engine, shared data model, security, widgets, and XML metadata. The safe strategy is a **strangler migration**: establish the Azure platform and seams first, then extract bounded contexts one at a time while the monolith continues to serve unconverted behavior.

## 2. Goals and non-goals

### Goals

- Independently build, deploy, scale, observe, and roll back business services.
- Give every service explicit API/event contracts and exclusive ownership of its data.
- Replace direct cross-domain database access with APIs, events, and replicated read models.
- Support zero-downtime migration and controlled coexistence with OFBiz.
- Use repeatable infrastructure as code and automated environment promotion.
- Use Azure managed services where they remove undifferentiated operational work.
- Build security, auditability, resilience, cost controls, and disaster recovery into the platform.
- Preserve business behavior with characterization, contract, reconciliation, and end-to-end tests.

### Non-goals

- Rewriting all OFBiz features before delivering business value.
- Translating each OFBiz component or Java package into one microservice.
- Sharing OFBiz entity tables between new services as a permanent architecture.
- Reproducing every legacy framework abstraction in a new shared framework.
- Introducing Kubernetes unless scale, networking, portability, or operational requirements justify it.

## 3. Evidence and architectural constraints

Sonar's current Java architecture view reports 71 top-level package nodes and 399 dependencies. Business packages depend heavily on `org.apache.ofbiz.entity`, `org.apache.ofbiz.service`, `org.apache.ofbiz.webapp`, `org.apache.ofbiz.security`, and `org.apache.ofbiz.base.*`. Accounting also reaches into order, party, product, and store capabilities. The current intended-architecture model contains no constraints.

These facts imply:

- package boundaries are not reliable service boundaries;
- the shared database is a primary coupling mechanism;
- service extraction must include data ownership, not only HTTP wrappers;
- cross-domain transactions must become workflows with compensating actions;
- XML service, entity, ECA, screen, and controller metadata must be included in discovery;
- migration order must follow dependency and business-flow analysis.

## 4. Decisions to record before implementation

Create Architecture Decision Records under `docs/adr/`. No implementation wave may proceed while its required ADR is unresolved.

| ADR | Default decision | Decision trigger |
| --- | --- | --- |
| ADR-001 Deployment platform | Azure Container Apps | Choose AKS only for requirements Container Apps cannot meet. |
| ADR-002 Infrastructure language | Terraform | Use Bicep only where organization policy mandates Azure-native templates. Do not maintain Terraform and Bicep for the same resources. |
| ADR-003 Service runtime | Java 21 LTS with Spring Boot 3.x | A context may choose another runtime only with ownership and support justification. |
| ADR-004 Synchronous API style | REST/JSON with OpenAPI 3.1 | Introduce gRPC only for measured internal latency/throughput needs. |
| ADR-005 Event transport | Azure Service Bus Premium | Event Hubs is reserved for high-volume telemetry/streaming, not business commands. |
| ADR-006 Operational database | Azure Database for PostgreSQL Flexible Server | Select another store per service only from measured access-pattern requirements. |
| ADR-007 Identity | Microsoft Entra ID / External ID and managed identities | Preserve local OFBiz login only behind the migration façade while needed. |
| ADR-008 Edge | Azure Front Door Premium plus API Management | Review regional/internal-only deployments separately. |
| ADR-009 Observability | OpenTelemetry, Application Insights, Log Analytics | Define retention, sampling, and PII rules before production. |
| ADR-010 Repository model | Monorepo initially | Reconsider after team ownership and release independence are proven. |

### Why Terraform rather than ARM

Raw ARM JSON should not be the authoring format. Terraform is recommended because it supports modular composition, reviewable plans, provider ecosystems, policy checks, and consistent management of Azure plus adjacent services. Use remote state in a dedicated Azure Storage account with blob versioning, locking, private access, and tightly scoped identities.

Bicep is a valid alternative when the organization is Azure-only and has stronger Bicep expertise or policies. It is preferable to raw ARM templates, but mixing Bicep and Terraform for the same resource lifecycle creates ownership and drift problems.

## 5. Target architecture

```text
Users and partner systems
          |
 Azure Front Door + WAF
          |
   Azure API Management -------- Static Web Apps / frontend hosting
          |
  Container Apps environment
  +-----------------------------------------------+
  | Identity | Party | Catalog | Pricing | Order  |
  | Inventory | Fulfillment | Billing | Content  |
  | Manufacturing | Notifications | Reporting   |
  +-----------------------------------------------+
       | REST for queries/commands requiring reply
       | Service Bus topics/queues for integration events
       | Dapr optional, only after an ADR and measured need
       v
 Per-service PostgreSQL databases/schemas during transition
 Blob Storage | Redis | AI Search | Key Vault as required
          |
 OpenTelemetry -> Application Insights / Log Analytics

 Legacy OFBiz remains behind APIM during migration and participates
 through an anti-corruption layer, outbox/CDC adapters, and reconciliation.
```

### Azure service mapping

| Concern | Default Azure service |
| --- | --- |
| Global ingress, TLS, WAF | Azure Front Door Premium |
| API gateway and policy | Azure API Management |
| Container runtime | Azure Container Apps |
| Container images | Azure Container Registry |
| Relational data | Azure Database for PostgreSQL Flexible Server |
| Messaging | Azure Service Bus Premium |
| Cache | Azure Cache for Redis or its current Microsoft-recommended successor at implementation time |
| Objects/documents/media | Azure Blob Storage |
| Secrets and certificates | Azure Key Vault |
| Workload identity | Managed identities and workload identity federation |
| Observability | Application Insights, Log Analytics, Azure Monitor, managed dashboards/alerts |
| Configuration/feature flags | Azure App Configuration |
| DNS/private connectivity | Private DNS zones, private endpoints, VNet integration |
| Security posture | Defender for Cloud, Azure Policy, Microsoft Sentinel integration where applicable |
| CI/CD identity | GitHub Actions or Azure DevOps with OIDC federation; no stored cloud credentials |

### AKS decision gate

Stay with Container Apps unless at least one documented requirement demands AKS, such as custom Kubernetes operators, privileged workloads, specialized networking/service mesh behavior, GPU scheduling, extensive sidecars/daemonsets, or platform portability mandated by policy. Team familiarity alone is not sufficient justification for AKS operational overhead.

## 6. Proposed bounded contexts

The following are hypotheses, not automatic mappings from OFBiz folders. Validate them through domain workshops, entity ownership analysis, service call tracing, and business transaction mapping.

| Context/service family | Owns | Publishes examples | Consumes examples |
| --- | --- | --- | --- |
| Identity & Access | principals, credentials/federation links, roles, coarse permissions | `UserProvisioned`, `RoleAssignmentChanged` | party lifecycle events |
| Party | people, organizations, relationships, contacts | `PartyCreated`, `ContactChanged` | identity references |
| Product Catalog | products, variants, categories, features, associations | `ProductPublished`, `CatalogChanged` | content references |
| Pricing & Promotions | price lists, rules, promotions, eligibility | `PriceChanged`, `PromotionActivated` | product and customer segments |
| Inventory | stock, reservations, facilities, availability | `StockAdjusted`, `InventoryReserved` | order and shipment events |
| Order | carts/quotes if retained, sales and purchase orders, order state | `OrderPlaced`, `OrderCancelled` | party, catalog, price, inventory outcomes |
| Fulfillment & Shipping | shipments, picks, packs, carrier interactions | `ShipmentCreated`, `ShipmentDispatched` | order and inventory events |
| Billing & Payments | invoices, payment intents/transactions, refunds | `InvoiceIssued`, `PaymentCaptured` | order and party events |
| Accounting & Ledger | journals, accounts, periods, posting | `JournalPosted`, `PeriodClosed` | invoice, payment, inventory valuation events |
| Manufacturing | BOM, routing, work orders, MRP | `WorkOrderReleased`, `ProductionCompleted` | product, inventory, demand events |
| Work Management | work efforts, calendars, tasks | `WorkItemChanged` | party events |
| Content | content metadata, resources, publication | `ContentPublished` | party and product references |
| Marketing | campaigns, segmentation, tracking policies | `CampaignActivated` | party consent and catalog events |
| Notifications | email/SMS/webhook delivery and templates | `NotificationDelivered`, `NotificationFailed` | domain notification requests |
| Reporting/Analytics | read-only projections and analytical models | none as system of record | integration events from all contexts |

Do not create all of these on day one. A service is justified only when it has cohesive ownership, an independent lifecycle, and a team/operator capable of supporting it.

## 7. Mandatory microservice rules

Every extracted service must satisfy all of the following:

1. It owns its database objects; other services cannot query them directly.
2. It exposes versioned OpenAPI contracts and versioned event schemas.
3. It authenticates callers and authorizes operations at the service boundary.
4. It uses managed identity for Azure resources and retrieves secrets from Key Vault.
5. It propagates W3C trace context and structured correlation identifiers.
6. It implements health, readiness, metrics, logs, traces, and actionable alerts.
7. It has explicit timeouts, bounded retries with jitter, circuit breaking, and bulkheads where appropriate.
8. Message handlers are idempotent and tolerate duplicates and reordering where the contract permits.
9. Database changes are backward compatible across rolling deployments.
10. It publishes events through a transactional outbox; no database-plus-broker dual write.
11. It has unit, integration, API contract, event contract, security, migration, and smoke tests.
12. It can be deployed and rolled back without redeploying unrelated services.

## 8. Data decomposition strategy

Data is the hardest part of this migration. Treat database separation as a first-class program with named owners.

### 8.1 Build the ownership catalog

For every OFBiz entity and view entity, record:

- source definition and physical table;
- candidate owning bounded context;
- writers and readers;
- service and ECA usage;
- sensitivity/classification and retention;
- volume, growth, and latency requirements;
- relationships crossing candidate context boundaries;
- migration and reconciliation strategy.

Store this machine-readable catalog in `architecture/data-ownership.yaml` and validate it in CI. Each entity must have exactly one target owner or an explicit `legacy-only` disposition.

### 8.2 Transitional database isolation

Use these stages:

1. **Observe:** inventory reads/writes and establish reconciliation metrics.
2. **Encapsulate:** all new access goes through an anti-corruption API; stop adding direct cross-domain queries.
3. **Replicate:** seed the new store, then synchronize changes with outbox events or approved CDC tooling.
4. **Shadow:** execute new logic without customer-visible effects and compare results.
5. **Switch writes:** route authoritative writes to the new service; project required facts back to legacy temporarily.
6. **Switch reads:** route reads to the new API/read model.
7. **Retire:** remove legacy writers, replication, tables, and code after the rollback window.

Schema-per-service on one PostgreSQL server is acceptable as a short-lived cost optimization if identities and permissions enforce ownership. The target is independent database lifecycle and backup/restore boundaries; separate servers are required where security, noisy-neighbor, availability, or scale requirements demand them.

### 8.3 Distributed consistency

- Use local ACID transactions within one service.
- Use sagas/process managers for cross-service business flows.
- Define compensating actions and manual-recovery states explicitly.
- Never use distributed database transactions across services.
- Build read models from events where queries span contexts.
- Carry immutable business identifiers; do not reuse database primary keys as public contracts without review.
- Include event ID, type, schema version, source, subject, timestamp, trace context, tenant if applicable, and payload.

## 9. Strangler and coexistence design

Place APIM in front of both OFBiz and new services. Routing policies select the implementation by endpoint, tenant, user cohort, or feature flag. New clients must target gateway contracts rather than OFBiz URLs.

Create an `ofbiz-adapter` anti-corruption layer that:

- translates new canonical contracts to legacy service calls;
- hides `GenericValue`, generic maps, OFBiz status IDs, and internal entity keys;
- translates authentication and correlation context;
- converts legacy errors into stable problem details;
- publishes approved legacy changes to Service Bus;
- records reconciliation and migration telemetry.

The adapter is temporary. Give every adapter route an owner and retirement criterion. Do not allow new domain logic to accumulate there.

## 10. Delivery phases

Each phase ends with evidence and an explicit go/no-go review. Calendar estimates must be created only after discovery and team-capacity assessment.

### Phase 0 — Program bootstrap and guardrails

Deliverables:

- modernization charter, scope, business KPIs, and risk register;
- ADR template and initial ADRs from section 4;
- domain owner and platform owner assignments;
- engineering standards, threat-model template, and definition of done;
- cost budget, tagging standard, naming convention, and environment strategy;
- baseline production SLOs, performance, error rates, and critical user journeys;
- Sonar intended-architecture model defining allowed dependencies for newly introduced modules.

Exit criteria:

- executive product owner and technical owner approve scope;
- every critical business flow has an owner and measurable baseline;
- no unresolved platform decision blocks the foundation.

### Phase 1 — Discovery and characterization

Use Sonar navigation and runtime telemetry to build a dependency map. Search declarations first, then callers/callees and references for service, entity, web, and ECA symbols. Supplement static analysis with production traces and database auditing.

Deliverables:

- context map and event-storming results;
- entity ownership catalog;
- API/service inventory and consumer map;
- critical flow sequence diagrams;
- characterization tests against current OFBiz behavior;
- data quality profile and migration-volume measurements;
- extraction scorecard for candidate contexts.

Score candidates by business value, coupling, data complexity, change frequency, operational load, compliance risk, and rollback feasibility. Prefer an early slice that is valuable but not financially irreversible, such as notifications or a read-only catalog projection. Do not use accounting/ledger as the pilot.

Exit criteria:

- first extraction slice is selected from evidence;
- its owned data, upstream/downstream contracts, and rollback path are known;
- characterization tests cover its critical behavior.

### Phase 2 — Azure landing zone and IaC foundation

Create Terraform modules and live environment composition. The initial structure should be:

```text
infra/
  bootstrap/                 # state storage and CI identity; applied separately
  modules/
    resource-group/
    network/
    private-dns/
    container-apps-environment/
    container-app/
    container-registry/
    api-management/
    front-door/
    postgres/
    service-bus/
    key-vault/
    app-configuration/
    observability/
    policy/
  environments/
    dev/
    test/
    staging/
    prod/
  policies/
  tests/
```

Terraform requirements:

- pin Terraform, AzureRM, AzureAD, and auxiliary provider versions;
- remote encrypted state per environment with private access and versioning;
- separate subscriptions/resource groups according to the landing-zone model;
- OIDC workload federation for CI/CD;
- plan on pull requests and protected apply after approval;
- `fmt`, `validate`, linting, security scanning, policy checks, and plan review in CI;
- Azure Policy for allowed regions/SKUs, required tags, diagnostics, private networking, TLS, and public-access restrictions;
- module tests and ephemeral integration deployments;
- no secrets in variables, state outputs, repository, or pipeline logs;
- documented state recovery and provider-upgrade procedures.

Exit criteria:

- an ephemeral environment can be created and destroyed automatically;
- staging and production have private networking, diagnostics, budgets, and policy enforcement;
- platform recovery and access procedures have been tested.

### Phase 3 — Service golden path

Create a reference service and reusable templates, not a large proprietary framework.

Suggested repository structure:

```text
services/<service-name>/
  src/
  api/openapi.yaml
  events/asyncapi.yaml
  db/migrations/
  deploy/
  Dockerfile
  README.md
  OWNERS
  service.yaml
tests/
  contract/
  e2e/
```

Golden-path capabilities:

- Java 21/Spring Boot baseline and dependency locking;
- conventional ports-and-adapters package structure;
- OpenAPI-first request/response generation or validation;
- AsyncAPI/JSON Schema event definitions;
- PostgreSQL migrations with Flyway or Liquibase;
- transactional outbox and idempotency library/pattern;
- OAuth2/OIDC resource-server policy;
- OpenTelemetry instrumentation and standard dashboards;
- health/readiness endpoints and graceful shutdown;
- SBOM, signed image, vulnerability scan, provenance, and admission policy;
- Testcontainers integration tests and consumer-driven contract tests;
- deployment, canary, rollback, and smoke-test pipeline.

Exit criteria:

- a sample service deploys from commit to development automatically;
- the same immutable image is promoted through environments;
- rollback, secret rotation, broker outage, and database restore have been exercised.

### Phase 4 — Edge, identity, and legacy bridge

Deliver:

- Front Door/WAF and APIM routes for legacy and new workloads;
- Entra-based workforce/customer identity design;
- fine-grained authorization approach and permission mapping from OFBiz;
- `ofbiz-adapter` with contract and characterization tests;
- end-to-end trace propagation through gateway, adapter, OFBiz, services, and messaging;
- feature flags and cohort-based routing;
- event bridge/outbox or approved CDC path from OFBiz;
- dead-letter handling and replay tools with authorization/audit controls.

Exit criteria:

- one non-critical request and one event travel end-to-end with traceability;
- identity and permission equivalence tests pass;
- routing can revert to OFBiz without data loss.

### Phase 5 — Pilot extraction

Implement the selected low-risk vertical slice end to end. A recommended sequence is:

1. notifications or document/media delivery;
2. read-only product/catalog projection;
3. authoritative catalog/content capability;
4. party/contact capability;
5. inventory reservation;
6. order orchestration;
7. fulfillment/shipping;
8. billing/payments;
9. accounting/ledger;
10. manufacturing and remaining specialized modules.

The actual order must follow the scorecard. Each extraction uses the work package template in section 13.

Exit criteria:

- pilot meets production SLOs and security controls;
- reconciliation remains within approved tolerance for the full observation window;
- rollback has been demonstrated;
- operational load and unit cost are acceptable.

### Phase 6 — Domain migration waves

Run at most a small number of extraction streams concurrently. Each stream owns its service, data migration, legacy retirement, operations, and documentation. Maintain a program dependency board for shared events and gateway contracts.

For every wave:

- freeze new direct dependencies into the legacy area;
- define contract and data ownership before code generation;
- add consumer contract tests before changing providers;
- shadow and reconcile before switching authority;
- use canary/cohort rollout;
- remove legacy behavior after the rollback window;
- update the Sonar intended architecture and enforce it in CI.

### Phase 7 — Frontend modernization

Do not block backend decomposition on a complete UI rewrite. Initially route existing server-rendered pages through the edge. Replace screens by business journey using a modern frontend only where product value justifies it.

Recommended approach:

- one composable web application initially, not many micro-frontends;
- a backend-for-frontend if UI aggregation would otherwise leak service topology;
- Entra-compatible OIDC with secure browser session/token handling;
- accessibility, localization, observability, and performance budgets;
- feature flags to switch individual journeys from OFBiz screens.

Adopt micro-frontends only if independently releasing UI teams and domain ownership create a demonstrated need.

### Phase 8 — Monolith retirement

Retirement criteria:

- no production route targets the retired OFBiz capability;
- no new service reads or writes its legacy tables;
- retention, audit, legal hold, and export requirements are satisfied;
- rollback window has expired and final reconciliation is approved;
- jobs, ECA rules, credentials, infrastructure, monitoring, and licenses are removed;
- runbooks and ownership catalogs are updated;
- cost and attack-surface reductions are verified.

OFBiz itself is decommissioned only when all required capabilities have migrated or have an approved replacement/retirement decision.

## 11. CI/CD and supply-chain requirements

Every service pipeline must:

1. compile, lint, and run unit tests;
2. run architecture and dependency-boundary tests;
3. run SAST, secrets scanning, and dependency/license analysis;
4. run integration tests with real disposable dependencies;
5. validate OpenAPI and event schemas and run compatibility checks;
6. build one minimal, non-root, immutable container image;
7. generate an SBOM and vulnerability report;
8. sign the image and publish provenance;
9. deploy to an ephemeral or development environment;
10. run smoke, contract, security, and migration tests;
11. promote the exact artifact through staging and production;
12. use canary/blue-green rollout with automated health gates;
13. record deployment metadata and support one-command rollback.

Terraform has a separate pipeline: validate and plan on pull requests; policy/security checks on the plan; approval and apply from a protected branch; drift detection on a schedule; no service pipeline may apply shared platform infrastructure.

## 12. Security, reliability, and operations

### Security baseline

- Threat-model every context and cross-trust-boundary flow.
- Use private endpoints and deny public data-plane access by default.
- Use managed identities and least-privilege RBAC; prohibit long-lived client secrets.
- Encrypt in transit and at rest; define customer-managed-key requirements explicitly.
- Classify PII/payment/financial data and implement retention/deletion controls.
- Tokenize or delegate payment-card handling to a compliant provider; minimize PCI scope.
- Put rate limits, quotas, schema validation, and threat protection at APIM/WAF and services.
- Audit administrative and business-sensitive operations immutably.
- Automate patching, image rebuilds, secret/certificate rotation, and access reviews.

### Reliability baseline

Define per-service SLIs/SLOs for availability, latency, correctness, freshness, and queue delay. Error budgets govern release pace. Every service must document:

- dependency timeouts and retry budgets;
- degraded behavior;
- queue backlog and dead-letter thresholds;
- RPO/RTO and tested restore process;
- zone and region strategy;
- capacity/autoscaling limits;
- incident runbook and owner;
- load, stress, soak, and chaos-test results appropriate to criticality.

Avoid active-active multi-region data designs unless the business requirements justify their consistency and operational cost. Begin with zone redundancy and a tested regional recovery design.

### Observability baseline

- standard semantic conventions and structured logs;
- trace IDs in HTTP, messages, jobs, and support-visible error responses;
- RED metrics for APIs and queue/database metrics for workers;
- business metrics such as order acceptance, reservation failure, invoice issuance, and reconciliation drift;
- dashboards by service and critical journey;
- symptom-based alerts tied to runbooks, with noise budgets;
- log redaction and telemetry retention policies.

## 13. AI-agent extraction work package

Create one file under `modernization/work-items/` per extraction. An agent must not begin implementation until all inputs below are populated and approved.

```yaml
id: EXTRACT-<context>-<number>
title: <single bounded outcome>
owner: <team/person>
bounded_context: <name>
business_capability: <capability>
source_scope:
  java_fqns: []
  xml_resources: []
  entities: []
  services: []
  eca_rules: []
consumers: []
contracts:
  openapi: <path>
  asyncapi: <path>
data:
  owner: <service>
  classification: <classification>
  seed_strategy: <strategy>
  synchronization: <outbox-or-cdc>
  reconciliation_queries: []
migration:
  feature_flag: <name>
  shadow_mode: <description>
  cutover: <steps>
  rollback: <steps>
non_functional:
  slo: <target>
  rpo: <target>
  rto: <target>
  peak_load: <measured value>
acceptance_tests: []
out_of_scope: []
dependencies: []
retirement_criteria: []
```

### Required agent execution sequence

For each work package, the implementing agent must:

1. Retrieve project coding/security/testing/architecture guidelines.
2. Use Sonar navigation to discover exact FQNs; never infer symbol names or use text search as a substitute for semantic navigation.
3. Trace callers, callees, hierarchy, and references for every source service/entity boundary.
4. Inspect all linked XML service, entity, ECA, controller, and screen metadata after required secrets checks.
5. Update the ownership catalog and context map before implementation.
6. Write or update ADRs for any decision not covered by the defaults.
7. Define OpenAPI and event schemas before provider implementation.
8. Add characterization and consumer contract tests that fail before the change.
9. Implement the smallest vertical slice, including telemetry and operational endpoints.
10. Add backward-compatible database migrations, outbox, idempotency, and reconciliation.
11. Add Terraform only through approved reusable modules; never embed credentials.
12. Run unit, integration, contract, security, architecture, migration, and smoke tests.
13. Deploy to a disposable environment and capture evidence.
14. Exercise shadow, cutover, and rollback procedures.
15. Update diagrams, runbooks, API catalog, event catalog, ownership, and retirement status.
16. Run final deep static analysis on every changed file and resolve in-scope findings.

### Agent stop conditions

The agent must stop and request a human decision when:

- data ownership is ambiguous;
- a public contract requires a breaking change without a migration/versioning decision;
- payment, identity, authorization, financial posting, privacy, or legal-retention semantics are unclear;
- destructive migration lacks a verified backup, restore, and reconciliation path;
- required Azure permissions or production access exceed the work package authorization;
- a new external dependency fails security, vulnerability, or license policy;
- rollback cannot be made safe;
- the requested work would create direct cross-service database access.

## 14. Per-service definition of done

A service is production-ready only when:

- bounded context and data ownership are documented;
- API and event contracts are versioned and compatibility-tested;
- security review and threat model are complete;
- tests cover business rules, persistence, contracts, migration, and failure modes;
- dashboards, alerts, SLOs, runbook, and on-call owner exist;
- IaC creates all required resources without manual portal steps;
- managed identities and private connectivity are verified;
- backup/restore and disaster recovery meet RPO/RTO;
- load and resilience tests meet targets;
- canary rollout and rollback have succeeded in staging;
- SBOM, signing, provenance, and vulnerability policies pass;
- reconciliation proves functional/data equivalence within approved tolerances;
- legacy code/data retirement tasks are scheduled and owned.

## 15. Program metrics

Track outcomes, not service count:

- deployment frequency and lead time by context;
- change failure rate and mean time to restore;
- SLO attainment and error-budget consumption;
- percentage of traffic and authoritative writes served outside OFBiz;
- number of direct cross-context database accesses remaining;
- reconciliation defects and migration-related incidents;
- time to provision an environment/service;
- vulnerability remediation and secret-rotation time;
- infrastructure and platform cost per key business transaction;
- legacy code, tables, routes, jobs, and infrastructure retired;
- developer onboarding and cognitive-load surveys.

Creating many small services is not success. Improved change safety, ownership, resilience, and business delivery are success.

## 16. Key risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Distributed monolith | Enforce data ownership, contracts, intended architecture, and independent deployment gates. |
| Big-bang rewrite | Strangler routing, vertical slices, feature flags, shadowing, and reversible cutovers. |
| Data divergence | Outbox/CDC, idempotency, reconciliation, ownership switch gates, and audit trails. |
| Excess service count | Start coarse-grained; split only on measured ownership/scale/change pressure. |
| Cross-service latency | Prefer events/read models, avoid chatty APIs, and set journey latency budgets. |
| Lost OFBiz behavior hidden in metadata | Inventory XML services/entities/ECA/controllers/screens and add characterization tests. |
| Cloud cost growth | Budgets, tagging, autoscaling bounds, right-sizing, lifecycle policies, and unit economics. |
| Platform-team bottleneck | Self-service golden path with paved-road modules and clear exception process. |
| Security regression | Central identity/policy guardrails plus service-level authorization and threat models. |
| Permanent migration scaffolding | Owners, expiry dates, dashboards, and retirement criteria for every adapter/replication path. |

## 17. First backlog

The first implementation backlog should contain these ordered epics:

1. Approve charter, owners, ADRs, SLO vocabulary, and modernization definition of done.
2. Generate the entity/service/ECA/consumer ownership catalog using Sonar navigation plus runtime evidence.
3. Model critical journeys: login, product browse, order placement, reservation, shipment, invoicing, payment, and ledger posting.
4. Add characterization tests and baseline production telemetry for those journeys.
5. Create Terraform bootstrap and landing-zone modules with CI federation and policy checks.
6. Deploy the Container Apps golden-path reference service with PostgreSQL, Service Bus, Key Vault, and OpenTelemetry.
7. Put Front Door/APIM in front of a non-critical OFBiz route and prove instant rollback.
8. Implement the OFBiz anti-corruption adapter and trace propagation.
9. Implement and productionize the selected pilot extraction.
10. Review pilot metrics and revise templates, ADRs, estimates, and migration sequencing before scaling the program.

## 18. Local development requirement

The application must be runnable on a developer MacBook without requiring a permanently connected personal Azure environment. A developer must be able to start only the service or small group of services needed for a task rather than the complete platform.

### Local stack

Use the following defaults:

- Docker Desktop or Colima for the container runtime;
- Docker Compose for service dependencies and multi-service development;
- native JVM execution for the service being actively changed, with dependencies in containers;
- PostgreSQL containers for service databases;
- Redis containers where caching is required;
- Azurite for Azure Blob Storage emulation;
- the Azure Service Bus emulator where its supported behavior is sufficient, otherwise an adapter-backed local broker or an isolated shared Azure development namespace;
- Keycloak or a development JWT issuer for local Entra-compatible OAuth2/OIDC tokens;
- OpenTelemetry Collector with Jaeger and/or Grafana for local traces and metrics;
- Testcontainers for repeatable integration and contract tests.

Each service must provide a documented one-command startup path, such as `docker compose up`, a Gradle task, or a repository development script. Bootstrap must create databases, apply migrations, seed minimal non-sensitive test data, and expose health checks. Local startup must not require production credentials or secrets committed to the repository.

### Portability and configuration rules

- Keep Azure SDK and platform-specific behavior behind application ports/adapters where a local substitute is necessary.
- Use the same business code and container image locally and in Azure; only configuration and infrastructure adapters may vary.
- Select adapters through validated environment configuration or application profiles.
- Keep example local configuration free of credentials and use ignored local overrides for developer-specific values.
- Use deterministic fixtures and fake external providers for payment, shipping, email, and other third-party integrations.
- Do not make successful access to Azure a prerequisite for unit tests or routine local integration tests.
- Run contract tests against both local substitutes and real Azure services in CI or a shared development environment where emulator behavior may differ.
- Support Apple Silicon images and dependencies; document any architecture-specific limitation.
- Define laptop resource budgets and allow optional services to remain stopped.

### Local orchestration layout

Add a local-development area similar to:

```text
dev/
  compose.yaml
  compose.observability.yaml
  config/
  fixtures/
  identity/
  scripts/
```

Service documentation must state:

1. prerequisites and supported macOS/container-runtime versions;
2. startup and shutdown commands;
3. ports and local URLs;
4. how to obtain a development identity/token;
5. how to seed and reset that service's data safely;
6. how to run tests and inspect traces/messages;
7. which features use an emulator, fake, or shared Azure dependency;
8. known differences from Azure behavior.

### Azure-only verification

Some platform behavior cannot be faithfully reproduced on a laptop. The delivery pipeline must therefore test these capabilities in ephemeral or shared Azure environments:

- API Management policies and gateway identity propagation;
- Front Door routing and WAF behavior;
- managed identities and Azure RBAC;
- private endpoints, Private DNS, and VNet integration;
- Azure Policy and Defender controls;
- production Service Bus characteristics, failover, and dead-letter behavior;
- Key Vault authorization and rotation;
- Azure autoscaling, zone redundancy, backup, restore, and disaster recovery.

Local emulators accelerate development but are not compliance or production-equivalence evidence. Promotion gates must include tests against the actual managed Azure services.

### Local-development acceptance criteria

- A new developer can run one reference service and its dependencies on a supported MacBook using documented commands.
- The setup works on Apple Silicon without source changes.
- Startup uses only non-sensitive development data and credentials.
- Routine unit and integration tests run without Azure connectivity.
- Local HTTP, database, event, and trace flows are observable.
- Resource usage remains within the documented laptop budget.
- CI verifies the same contracts against real Azure services before production promotion.

## 19. Final recommendation

Use **Terraform + Azure Container Apps + API Management + Service Bus + PostgreSQL + Entra ID + OpenTelemetry** as the default platform. Preserve the option to move selected workloads to AKS, but do not make Kubernetes a prerequisite.

Begin with architecture discovery, data ownership, platform guardrails, and one reversible vertical slice. Keep services coarse-grained until genuine independent ownership emerges. Move authority over data deliberately, prove equivalence through reconciliation, and retire each legacy path rather than leaving a permanent hybrid system.

The essential sequence is:

```text
Discover -> Characterize -> Establish platform -> Define contracts
-> Extract one vertical slice -> Shadow and reconcile -> Cut over
-> Retire legacy behavior -> Repeat
```

Following that sequence turns the modernization into a series of controlled, measurable changes instead of a high-risk rewrite.
