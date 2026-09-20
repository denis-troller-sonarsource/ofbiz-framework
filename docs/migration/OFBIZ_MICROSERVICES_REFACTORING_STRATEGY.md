# OFBiz Microservices Refactoring Strategy

## Recommendation

Do not attempt the full OFBiz-to-microservices migration in one agent session or one large refactoring branch.

Use a hybrid strangler migration:

- Keep OFBiz running.
- Put a gateway in front of old and new capabilities.
- Build the Azure/local foundation first.
- Extract one bounded service at a time.
- Route selected APIs/use cases to new services.
- Keep old OFBiz screens/routes alive until replacement is proven.
- Move data ownership domain by domain.
- Retire OFBiz areas gradually.

## Modern Service Technology Decision

All modern application services in this migration must be Java-based to preserve JVM,
Gradle, tooling, and operational continuity with OFBiz. Use Java 17 or the repository's
current supported LTS version and build services with Gradle. The standard service template
must use either Spring Boot 3 or Quarkus; select one framework for the program and use it
consistently across extracted services.

Python, Node.js, and other runtimes must not be used for production services or Phase 1
demonstration services. They remain acceptable for repository tooling, discovery scripts,
and one-off migration utilities that are not deployed as application services.

## Why Not A Complete Refactor

A complete rewrite/refactor is high risk because OFBiz is a large ERP system with heavy coupling across:

- XML entity models.
- Service definitions.
- Service ECA hooks.
- Entity ECA hooks.
- Seed/demo data.
- Server-rendered screens/forms/menus.
- Scheduled jobs.
- Accounting/order/product workflows.
- Shared relational schema.

Risks of complete refactor:

- Long time before usable value.
- Hard to validate parity.
- Hidden business rules missed.
- Hard rollback.
- Accounting and order regressions.
- New services may recreate monolith coupling with distributed complexity.

Complete refactor should only be considered after enough domains have been extracted and the remaining legacy surface is small.

## Feasible Hybrid Shape

Old and new systems can coexist behind API Management or a similar gateway.

```text
Azure API Management / local gateway
  /webtools            -> OFBiz legacy
  /ordermgr            -> OFBiz legacy
  /accounting          -> OFBiz legacy
  /api/catalog         -> new Product Catalog Service
  /api/notifications   -> new Notification Service
  /api/parties         -> new Party Service
  /api/orders          -> OFBiz adapter first, new Order Service later
```

During migration:

- OFBiz remains source of truth for domains not yet extracted.
- New services own their own databases.
- The legacy adapter isolates OFBiz-specific APIs/entities.
- Backfill jobs copy initial data into service databases.
- Events/outbox keep new read models synchronized.
- Route cutover happens per endpoint/use case.
- Rollback means routing traffic back to OFBiz for that domain.

## Minimal Viable Modern Phase 1

Phase 1 must produce a runnable hybrid baseline, not only discovery files. A developer must be able to start one local stack, log into the OFBiz UI, view a migrated Accounting Invoices slice, and call endpoints served by the modern stack.

Phase 1 target experience:

- The OFBiz UI remains available and looks the same as before.
- Login still uses the legacy OFBiz user flow.
- Legacy screens such as Webtools, Order Manager, Accounting, Party Manager, and Product Catalog still run from OFBiz.
- A modern gateway runs locally and becomes the preferred entry point for the hybrid system.
- The Accounting > Invoices section has a modern read-only service slice running next to OFBiz.
- The modern invoice service is reachable through the gateway.
- The legacy OFBiz stack and the modern stack use non-conflicting local ports.

Phase 1 local routes:

```text
https://localhost:18080/webtools                 -> legacy OFBiz UI through modern gateway
https://localhost:18080/accounting/control/findInvoices -> modern Accounting Invoices search replacing legacy route
https://localhost:18080/modern/accounting/invoices -> modern Accounting Invoices UI through modern gateway
https://localhost:18080/api/accounting/invoices  -> modern Accounting Invoices API through modern gateway
https://localhost:18080/api/notifications/health -> modern Notification Service through modern gateway
https://localhost:18443/webtools                 -> direct legacy OFBiz fallback/debug URL
https://localhost:18443/accounting/control/findInvoices -> direct legacy invoice-search fallback/debug URL
```

Phase 1 components:

| Component | Role |
| --- | --- |
| `legacy-ofbiz-app` | Existing OFBiz monolith container |
| `legacy-ofbiz-db` | Local PostgreSQL for OFBiz demo data |
| `modern-gateway` | Local reverse proxy standing in for APIM during dev |
| `modern-accounting-invoice-service` | First meaningful modern domain slice for Accounting > Invoices |
| `modern-notification-service` | Auxiliary proof-of-life health service |

Phase 1 acceptance criteria:

- `docker compose -p erp-local -f local-dev/docker-compose.yml up -d` starts the hybrid stack.
- `https://localhost:18080/webtools` loads the OFBiz UI.
- User can log in with `admin` / `ofbiz`.
- `https://localhost:18080/accounting/control/findInvoices` is served by the modern invoice service, not legacy OFBiz.
- `https://localhost:18443/accounting/control/findInvoices` remains the direct legacy fallback for comparison/debugging.
- `https://localhost:18080/modern/accounting/invoices` loads a modern invoice list UI.
- `https://localhost:18080/api/accounting/invoices?limit=10` returns invoice headers, status/type labels, parties, totals, payment applications, and outstanding amounts from the modern service.
- `https://localhost:18080/api/accounting/invoices/{invoiceId}` returns invoice detail with line items, payment applications, and status history.
- The modern invoice API supports limited create/update/delete operations for invoice headers, while legacy OFBiz remains responsible for invoice lines, payment application, posting, tax, promotion, PDF, and accounting side effects.
- The modern invoice UI keeps navigation links back to the remaining legacy Accounting sections so users can move between strangled and non-strangled flows.
- `https://localhost:18080/api/notifications/health` returns a healthy JSON response from the modern service.
- Direct legacy fallback `https://localhost:18443/webtools` still works.
- Direct legacy fallback emits same-origin legacy asset and form URLs on `https://localhost:18443`, not gateway URLs.
- Gateway-served legacy pages emit gateway asset and form URLs on `https://localhost:18080`.
- Representative legacy UI assets return 200 through both paths, including `/helveticus/HELVETICUS_EMERALD.less`, `/helveticus/style.css`, and `/common/js/node_modules/jquery-ui-dist/jquery-ui.min.js`.
- No host port conflicts with default OFBiz ports.
- The gateway config makes it obvious which paths are legacy and which paths are modern.

Phase 1 local UI lessons learned:

- Treat `https://localhost:18080` and `https://localhost:18443` as separate browser origins. Simple CSS and JS can load cross-origin when allowed, but OFBiz Helveticus uses `less.js`; it fetches `.less` files through XHR and is sensitive to cross-origin, certificate, and CORS behavior.
- The direct legacy fallback must be same-origin for legacy assets. When a user opens `https://localhost:18443/webtools`, generated links, scripts, stylesheets, LESS files, and login form actions should stay on `https://localhost:18443`.
- The gateway path must rewrite legacy-generated absolute URLs to the gateway origin. When a user opens `https://localhost:18080/webtools`, generated legacy links, scripts, stylesheets, LESS files, and login form actions should resolve to `https://localhost:18080`.
- Do not configure OFBiz itself to emit the gateway port if direct legacy fallback is part of the supported dev flow. Configure OFBiz for its direct external port, then let the gateway rewrite `https://localhost:18443/...` to `https://localhost:18080/...` for gateway traffic.
- OFBiz Docker startup may skip config hooks after `/ofbiz/runtime/container_state/config_applied` exists. Local dev compose should force URL config to be reapplied when URL-related env vars or hook scripts change.
- The OFBiz image must include or mount theme NPM dependencies. Building with `-PskipNpmInstall` avoids slow/fragile NPM work, but then `/common/js/node_modules/...` UI assets are missing and legacy screens render with 404s. For local dev, either build without `-PskipNpmInstall` or run `npm ci` for `themes/common-theme/webapp/common-theme/js` and mount its `node_modules` into the container.
- Smoke tests must validate rendered HTML and asset fetches, not only page HTTP status. Check at least one login form action, one stylesheet, one LESS file, and one JS vendor asset for both gateway and direct legacy URLs.

Phase 1 Accounting Invoices scope:

- Start with invoice list/detail projection and limited invoice-header CRUD instead of full write cutover.
- Strangle the existing legacy route `/accounting/control/findInvoices` at the gateway. This demonstrates endpoint-by-endpoint migration while the rest of `/accounting/control/*` still falls through to OFBiz.
- Source data from legacy OFBiz Postgres tables: `invoice`, `invoice_item`, `payment_application`, `invoice_status`, `invoice_type`, and `status_item`.
- Expose modern endpoints under `/api/accounting/invoices` and a simple UI under `/modern/accounting/invoices`.
- Keep OFBiz as the source of truth for full invoice lifecycle behavior: invoice items, status side effects, posting, payment application, tax, promotion, and PDF generation. The modern service only owns limited invoice-header CRUD for the Phase 1 demo.
- Preserve user navigation in the modern page. A strangled route must still expose links back to legacy Payments, Payment Groups, Transactions, Billing Accounts, Financial Accounts, Tax Authorities, Agreements, Fixed Assets, Budgets, GL Settings, Companies, and global modules.
- Show migration complexity explicitly: item total calculation, payment application joins, invoice status/type mapping, legacy party IDs, tax/promotion negative lines, and rounding deltas such as invoice `8009` where SQL-derived total differs from applied payment by a fractional cent.
- Before any write migration, reconcile the modern total calculation against OFBiz `InvoiceWorker`, accounting service ECAs, and GL posting rules.

Phase 1 implementation contract for a fresh agent:

- Do not choose a new domain. Implement Accounting > Invoices as the first strangler slice.
- Keep legacy OFBiz running unchanged behind the gateway. Do not remove or rewrite OFBiz Java invoice services in Phase 1.
- Add `services/accounting-invoice-service/` as a separate Java service built with Gradle and the program's selected Spring Boot 3 or Quarkus template. It must run as an external process and expose an HTTP API behind the gateway; a Python or other non-JVM service is not acceptable for the demo.
- The service must connect to the existing local OFBiz Postgres database using the OFBiz demo DB credentials already present in `docker/examples/postgres-demo/ofbiz-postgres.env`.
- Wire the service into `local-dev/docker-compose.yml` as `modern-accounting-invoice-service`, dependent on `legacy-ofbiz-db` health.
- Wire `local-dev/gateway/nginx.conf` so these paths go to the new service: `/api/accounting/invoices`, `/modern/accounting/invoices`, and exact legacy route `/accounting/control/findInvoices`.
- Keep all other `/accounting/control/*` paths routed to legacy OFBiz.
- Keep direct legacy fallback on `https://localhost:18443/accounting/control/findInvoices` so a demo can compare modern vs legacy behavior.
- The modern UI served at `/accounting/control/findInvoices` must look continuous with Helveticus Emerald: OFBiz logo in the top-left, header color `#1BC5BD`, light accent `#dcfffd`, dark accent `#133d3b`, and navigation back to remaining legacy modules/Accounting sections.
- The modern UI must contain a search form that filters through the modern service, not legacy OFBiz. Supported filters: `invoiceId`, `invoiceTypeId`, `statusId`, `partyIdFrom`, and `partyId`.
- The modern UI must link invoice IDs to `/api/accounting/invoices/{invoiceId}` and also provide a legacy detail link to `/accounting/control/viewInvoice?invoiceId={invoiceId}`.
- The modern list API must support `GET /api/accounting/invoices` with `limit`, `offset`, `invoiceId`, `invoiceTypeId`, `statusId`, `partyIdFrom`, and `partyId` query params.
- The list API response must include invoice ID, type/description, status/description, from/to parties, invoice dates, currency, item count, SQL-derived invoice total, applied payment total, outstanding total, pagination, and source marker `legacy-ofbiz-postgres`.
- The detail API must support `GET /api/accounting/invoices/{invoiceId}` and include header fields, line items, payment applications, and status history.
- The write demo must support limited invoice-header operations only: `POST /api/accounting/invoices`, `PUT /api/accounting/invoices/{invoiceId}`, and `DELETE /api/accounting/invoices/{invoiceId}`.
- Write scope must stay narrow: create/update invoice header fields only. Do not implement invoice-item mutation, payment application, posting, tax behavior, promotion behavior, GL side effects, or PDF generation in Phase 1.
- Delete must be guarded: allow only invoices without invoice items and payment applications; return conflict when dependencies exist.
- API notes must explicitly state totals are SQL-derived and must be reconciled with OFBiz `InvoiceWorker` before write cutover.
- Keep `modern-notification-service` only as auxiliary health/proof-of-life if already present; it is not the meaningful Phase 1 domain slice.
- Do not inspect or fix SonarQube findings. The repository is used for a SonarQube demo, and findings are demo material.

Phase 1 verification contract:

- Start with `docker compose -p erp-local -f local-dev/docker-compose.yml up -d --build`.
- Verify gateway strangling: `https://localhost:18080/accounting/control/findInvoices?invoiceId=8009` returns the modern invoice UI and contains the OFBiz logo, Emerald color markers, Accounting navigation, `Legacy detail`, and invoice `8009`.
- Verify legacy fallback: `https://localhost:18443/accounting/control/findInvoices` still returns legacy OFBiz behavior, usually the OFBiz login page if unauthenticated.
- Verify non-strangled Accounting path: `https://localhost:18080/accounting/control/findPayments` still routes to legacy OFBiz.
- Verify service health: `https://localhost:18080/api/accounting/invoices/health` returns status `UP` and service `modern-accounting-invoice-service`.
- Verify list API: `https://localhost:18080/api/accounting/invoices?limit=2` returns invoice JSON from the modern service.
- Verify detail API: `https://localhost:18080/api/accounting/invoices/8009` returns line items and payment applications.
- Verify create/update/delete with a temporary `MSVCTEST...` invoice header, then delete it so local demo data is not polluted.
- Verify legacy UI asset and port behavior separately: gateway-served legacy pages use `https://localhost:18080` assets, direct legacy pages use `https://localhost:18443` assets, and key Helveticus/CSS/JS/LESS assets return HTTP 200.
- Verify GitHub workflow status only. Do not open SonarQube issue lists or change code based on SonarQube findings.

Phase 1 SonarQube and CI contract:

- Remove competing GitHub analysis before adding SonarQube: Dependabot config, CodeQL workflow, GitHub Dependency Review workflow, OpenSSF Scorecard workflow, CodeQL/SARIF upload usage, and related README badges.
- Keep SonarQube as the only repository analysis signal for the demo. Do not add CodeQL, Dependabot, Dependency Review, OpenSSF Scorecard, or SARIF upload workflows back unless demo scope changes.
- Use the SonarQube Gradle plugin in `build.gradle`: `org.sonarqube` version `7.3.1.8318` with project key `e-corp-demo_ofbiz-framework` and organization `e-corp-demo`.
- Do not commit `SONAR_TOKEN`. GitHub Actions must read it from `${{ secrets.SONAR_TOKEN }}` only.
- Split SonarQube workflows by analysis type, so PR analysis cannot be confused with branch analysis.
- `build.yml` is the SonarQube PR workflow. It triggers only on `pull_request` targeting `trunk` or `feat-*`.
- The SonarQube PR workflow must pass these properties explicitly: `sonar.pullrequest.key=${{ github.event.pull_request.number }}`, `sonar.pullrequest.branch=${{ github.head_ref }}`, and `sonar.pullrequest.base=${{ github.base_ref }}`.
- For the Phase 1 PR, `sonar.pullrequest.base` must resolve to `trunk`. This means SonarQube analyzes all PR changes against the target branch, not only the latest commit.
- `sonarqube-branch.yml` is the SonarQube branch workflow. It triggers on `push` to `trunk` and `feat-*` and runs normal branch analysis.
- SonarQube project New Code policy is expected to be version-based for the demo. The workflow still must set PR branch/base metadata explicitly; do not rely on default auto-detection.
- SonarQube may flag issues in the migrated slice. That is intended demo material. Do not inspect, triage, suppress, or fix those findings while preparing the demo unless the user explicitly changes this rule.
- GitHub Gradle CI can fail if `javadoc` tries to fetch external documentation links, for example Groovy API docs, and the runner network times out. CI should run `./gradlew check javadoc -PdisableJavadocExternalLinks`; `build.gradle` should keep external Javadoc links enabled by default but skip them when this property is set.

## Data Coexistence Rules

Use strict data ownership even during the hybrid phase.

Rules:

- Only one writer per domain at any time.
- No new service writes directly into the OFBiz database.
- No service reads another service's database.
- Temporary read-only OFBiz DB access is allowed only inside the legacy adapter or migration jobs.
- New services communicate through APIs and events.
- Domain ownership changes require backfill, event sync, reconciliation, cutover, and rollback plan.

Migration data flow:

```text
OFBiz DB
  -> backfill job
  -> new service DB

OFBiz domain changes
  -> outbox / event publisher
  -> Azure Service Bus
  -> new service projection/update
```

## Agent Execution Strategy

Do not run this as one huge autonomous task.

Use many bounded sessions. The main agent owns architecture/integration decisions. Subagents or subtasks can do focused work, but they should not independently redesign boundaries.

OpenCode supports subagents/subtasks through task delegation. Use them for bounded research/build/review tasks.

Good subagent use cases:

- Map OFBiz components/routes/services/entities.
- Analyze one bounded context.
- Build one Terraform module.
- Create one service skeleton.
- Review a diff for architecture/security risks.
- Triage failed tests/builds.

Bad subagent use cases:

- Redesign all service boundaries independently.
- Refactor multiple domains at once.
- Make cross-cutting architecture decisions without main-thread review.
- Modify unrelated services in parallel.

## Recommended Session Breakdown

### Session 1: Discovery Only

Goal:

Map current OFBiz structure without changing application behavior.

Tasks:

- Generate active component inventory.
- Generate webapp/mount-point inventory.
- Generate controller route inventory.
- Generate service definition inventory.
- Generate entity dependency inventory.
- Identify scheduled jobs and integrations.
- Produce markdown reports under `migration/discovery/`.

Use subagents:

- Subagent A: route/webapp scan.
- Subagent B: service/entity scan.
- Subagent C: integration/runtime scan.

Main agent:

- Consolidates findings.
- Determines initial bounded contexts.

Acceptance:

- Discovery reports exist.
- No product code behavior changed.

### Session 2: Foundation Skeleton

Goal:

Create repo foundation for Azure and local development.

Tasks:

- Create Terraform folder/module skeleton.
- Create local-dev Docker Compose skeleton.
- Create CI validation placeholders.
- Create shared contracts/platform folder structure.
- Create service template structure.

Use subagents:

- Subagent A: Terraform skeleton.
- Subagent B: local dev compose skeleton.
- Subagent C: service template scaffold.

Main agent:

- Ensures layout matches intended architecture.
- Reviews naming/package conventions.

Acceptance:

- Terraform validates or has documented stubs.
- Local dev docs exist.
- Service template builds/runs minimally.

### Session 3: Legacy OFBiz Stabilization

Goal:

Make the existing OFBiz monolith reliable as a legacy service.

Tasks:

- Normalize Docker build.
- Decide/fix npm build behavior for REST docs.
- Add or identify health endpoint.
- Externalize secrets/config.
- Ensure local Docker Compose works.
- Prepare Azure Container App deployment definition.

Use subagents:

- Subagent A: Docker/runtime analysis.
- Subagent B: health/smoke tests.
- Subagent C: Terraform Container App module wiring.

Main agent:

- Keeps changes minimal.
- Ensures OFBiz remains compatible with local and Azure profiles.

Acceptance:

- OFBiz runs locally.
- OFBiz can be deployed as a legacy container.
- Smoke tests pass.

### Session 4: Microservice Template

Goal:

Create one production-grade template service.

Tasks:

- Use Java 17 or the repository's current supported LTS version and Gradle.
- Use the program-standard Spring Boot 3 or Quarkus stack.
- Implement hexagonal package layout.
- Add health/readiness endpoints.
- Add OpenAPI generation.
- Add OpenTelemetry/logging.
- Add local/cloud config profiles.
- Add Key Vault/Managed Identity integration pattern.
- Add Service Bus publisher/consumer sample.
- Add DB migration sample.
- Add Dockerfile and CI.

Use subagents:

- Subagent A: service app scaffold.
- Subagent B: Azure integration stubs.
- Subagent C: tests/CI.

Main agent:

- Enforces intended architecture.
- Adds/validates architecture tests.

Acceptance:

- Template compiles, tests, and packages with the Gradle wrapper.
- Template runs locally.
- Template deploys to dev Azure target or has Terraform-ready deployment.
- Tests pass.

### Session 5: First Extracted Service

Recommended service:

Notification Service or Product Catalog read API.

Why:

- Lower risk than Order or Accounting.
- Clearer boundaries.
- Easier parity checks.
- Faster value.

Tasks for Notification Service:

- Create `services/notification-service` from template.
- Define OpenAPI endpoints.
- Define AsyncAPI event/command contract.
- Add service-owned DB schema.
- Add provider abstraction with local mock and cloud provider.
- Integrate OFBiz through adapter/event/call.
- Route through APIM/local gateway.
- Add tests, dashboard, alerts.

Acceptance:

- OFBiz can request a notification through the new service.
- Local run works on Mac.
- Cloud deployment works in dev.
- Failure handling visible.

### Session 6: Gateway And Contracts

Goal:

Make old/new routing explicit and testable.

Tasks:

- Add APIM route definitions in Terraform.
- Add local gateway or documented local routing approach.
- Add OpenAPI contracts.
- Add AsyncAPI contracts.
- Add contract tests.
- Add smoke tests through gateway.

Acceptance:

- Gateway routes old paths to OFBiz and new paths to new services.
- Contract tests run in CI.
- Rollback route documented.

### Session 7+: Repeat Domain Extractions

Recommended extraction order:

1. Notification Service.
2. Product Catalog read service.
3. Party Service.
4. Identity & Access bridge.
5. Inventory & Facility Service.
6. Order Service.
7. Accounting Service.
8. Manufacturing, Marketing, Content, Work Effort.

Accounting should be late because it needs strict reconciliation, auditability, and parallel run.

## Main Agent Responsibilities

The main agent should:

- Own architecture decisions.
- Keep one canonical plan updated.
- Control service boundaries.
- Review all subagent outputs.
- Prevent unrelated edits.
- Ensure tests/verification run.
- Keep rollback paths explicit.
- Ensure code matches Sonar intended architecture rules.

## Subagent Responsibilities

Subagents should:

- Work on one bounded task.
- Return concise findings or diffs.
- Avoid broad refactors.
- Avoid changing architecture decisions.
- Avoid editing overlapping files in parallel.
- Include verification instructions/results.

## Branching Strategy

Use small branches/PRs.

Recommended PR types:

- `discovery/*`
- `infra/*`
- `legacy/*`
- `template/*`
- `service/notification-*`
- `service/catalog-*`
- `gateway/*`
- `contracts/*`

Rules:

- One domain or infrastructure slice per PR.
- Avoid PRs that mix OFBiz refactor, Terraform, and new service implementation unless required for one vertical slice.
- Each PR includes verification notes.
- Each migration PR includes rollback notes.

## Hybrid Migration Guardrails

- Build new capabilities around OFBiz before cutting into OFBiz internals.
- Prefer adapter/event integration over direct DB mutation.
- Keep service-owned data strict.
- Use APIM route toggles/canary routing for cutover.
- Keep old route working until new route has smoke tests and monitoring.
- Add reconciliation before write cutover.
- Do not extract full Accounting first. The approved Phase 1 exception is the narrow Accounting > Invoices search/detail/header-CRUD slice described above, kept behind gateway strangling with OFBiz fallback.
- Do not share domain model libraries across services.
- Do not allow new services to import `org.apache.ofbiz.*` except `legacy-ofbiz-adapter`.

## Local Development Port And Naming Strategy

When legacy OFBiz and new microservices run together locally, they must not compete for the same host ports. This is mainly a local development requirement; production Azure deployment will use managed ingress, internal networking, DNS, API Management, and service discovery patterns instead of fixed developer-machine host ports.

Use explicit local port allocation and clear Docker naming. Do not rely on default ports such as `8443`, `8080`, or `5432` when both stacks may run at the same time.

Recommended naming convention:

| Area | Prefix/Name |
| --- | --- |
| Legacy OFBiz stack | `legacy-*` |
| New microservices stack | `modern-*` |
| Legacy Docker Compose project | `ofbiz-legacy` |
| Modern Docker Compose project | `erp-modern` |
| All-in-one local Compose project | `erp-local` |

Recommended container names:

```text
legacy-ofbiz-app
legacy-ofbiz-db
modern-gateway
modern-notification-service
modern-catalog-service
modern-party-service
modern-order-service
modern-accounting-service
modern-postgres
modern-azurite
modern-servicebus-emulator
```

Recommended local compose files:

```text
local-dev/docker-compose.legacy.yml
local-dev/docker-compose.modern.yml
local-dev/docker-compose.yml
```

Usage examples:

```shell
docker compose -p ofbiz-legacy -f local-dev/docker-compose.legacy.yml up -d
docker compose -p erp-modern -f local-dev/docker-compose.modern.yml up -d
docker compose -p erp-local -f local-dev/docker-compose.yml up -d
```

Recommended local port allocation:

| Local host port | Component | Notes |
| --- | --- | --- |
| `18080` | Modern local API gateway | Main entry point for new services |
| `18443` | Legacy OFBiz HTTPS | Avoids conflict with default OFBiz `8443` |
| `18101` | Notification Service | Direct service access for debugging |
| `18102` | Product Catalog Service | Direct service access for debugging |
| `18103` | Party Service | Direct service access for debugging |
| `18104` | Inventory Service | Direct service access for debugging |
| `18105` | Order Service | Direct service access for debugging |
| `18106` | Accounting Service | Direct service access for debugging |
| `18107` | Legacy OFBiz Adapter | Direct service access for debugging |
| `15432` | Legacy OFBiz Postgres | Only if DB host access needed |
| `15433` | Modern shared/dev Postgres | Local dev only; production is service-owned DBs |
| `10000` | Azurite Blob | Standard Azurite blob port |
| `10001` | Azurite Queue | Standard Azurite queue port |
| `10002` | Azurite Table | Standard Azurite table port |

Guidelines:

- Use the `18xxx` range for local HTTP/HTTPS app ports.
- Use the `15xxx` range for local database/tool ports.
- Keep direct service ports for debugging, but route normal local traffic through `modern-gateway` on `18080`.
- Legacy OFBiz should be reachable locally at `https://localhost:18443` when running beside the modern stack.
- New services should be reachable through `https://localhost:18080/api/...` during normal local testing.
- Browser-facing local gateway traffic should use one canonical origin per page load. Do not mix `18080` and `18443` asset URLs in the same rendered page unless CORS and certificates are explicitly tested.
- Legacy UI smoke tests must inspect rendered `href`, `src`, and `form action` values, then fetch representative CSS, LESS, and JS assets.
- Avoid novelty prefixes such as `newera_`; use descriptive `legacy-*` and `modern-*` names.
- Document every exposed local port in `local-dev/README.md`.
- CI should fail if compose files publish duplicate host ports.

## Decision Matrix

| Question | Answer |
| --- | --- |
| One huge agent session? | No |
| Use subagents? | Yes, for bounded tasks only |
| Complete rewrite now? | No |
| Hybrid old/new feasible? | Yes |
| Recommended migration pattern | Strangler fig |
| Modern service runtime | Java on the current supported LTS JVM |
| Modern service framework | One program-wide choice: Spring Boot 3 or Quarkus |
| First service | Notification or Product Catalog read API |
| Hardest services | Order and Accounting |
| Main enforcement tools | Sonar Architecture, ArchUnit, OpenAPI, AsyncAPI, Terraform, CI smoke tests |

## Final Verdict

Use hybrid strangler migration. Start with foundation and one low-risk service. Keep OFBiz as the legacy system behind the gateway until individual domains are extracted, verified, and cut over.

Only consider full OFBiz retirement after enough domains are running independently and the remaining legacy surface is small, understood, and replaceable.


