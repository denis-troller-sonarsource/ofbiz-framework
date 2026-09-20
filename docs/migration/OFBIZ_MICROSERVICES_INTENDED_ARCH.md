# OFBiz Microservices Intended Architecture For SonarQube Cloud

## Purpose

This document defines the intended static code architecture for the planned OFBiz-to-microservices migration so it can be modeled in SonarQube Cloud Architecture.

SonarQube Cloud Architecture compares the current code architecture against an intended architecture. It works from code containers such as folders, modules, packages, classes, and files. Therefore, the intended architecture must be expressed through stable repository layout and package naming conventions.

## Key Point

Sonar Architecture enforces static code structure. It does not fully model Azure runtime topology.

Good Sonar Architecture use cases:

- Service A must not import Service B internals.
- `domain` must not depend on `infrastructure`.
- `api` or inbound adapters can call `application`.
- `application` can call `domain`.
- Adapters stay isolated.
- Legacy OFBiz access stays isolated.
- Cycles/tangles between packages are detected.

Not ideal for Sonar Architecture alone:

- Azure API Management route topology.
- Azure Service Bus topic wiring.
- Terraform module dependencies.
- Runtime REST calls between services.
- Deployment/runtime scaling behavior.

Those should be enforced with Terraform, OpenAPI, AsyncAPI, contract tests, integration tests, and CI/CD policy checks.

## Recommended Repository Layout

Use a monorepo during the migration unless the organization requires separate repositories.

```text
services/
  notification-service/
  product-catalog-service/
  party-service/
  inventory-service/
  order-service/
  accounting-service/
  manufacturing-service/
  marketing-service/
  content-service/
  work-effort-service/
  reporting-search-service/
  identity-access-service/
  legacy-ofbiz-adapter/
libs/
  platform/
  contracts/
  test-support/
infra/
  terraform/
migration/
  discovery/
  tests/
```

## Top-Level Sonar Containers

Create these top-level containers in SonarQube Cloud Intended Architecture:

| Container | Path | Purpose |
| --- | --- | --- |
| Services | `services/` | All independently deployable microservices |
| Libraries | `libs/` | Shared contracts/platform/test support |
| Infrastructure | `infra/` | Terraform/IaC files |
| Migration | `migration/` | Discovery scripts, migration utilities, smoke tests |
| Legacy OFBiz | existing OFBiz repo paths | Existing monolith code retained during strangler migration |

Recommended high-level allowed relationships:

```text
services -> libs/contracts
services -> libs/platform
services -> libs/test-support only from tests
infra -> no application code dependencies
migration -> legacy OFBiz + services only for tooling/tests
legacy OFBiz -> no new service internals
```

Forbidden high-level relationships:

```text
services/<service-a> -> services/<service-b>/src/main/internal packages
services/* -> legacy OFBiz internals, except legacy-ofbiz-adapter
libs/contracts -> services/*
libs/platform -> services/*
domain packages -> infrastructure/adapter packages
```

## Microservice Package Naming Convention

Use one root package per service.

Pattern:

```text
com.company.erp.<boundedcontext>
```

Examples:

```text
com.company.erp.notifications
com.company.erp.catalog
com.company.erp.parties
com.company.erp.inventory
com.company.erp.orders
com.company.erp.accounting
com.company.erp.manufacturing
com.company.erp.marketing
com.company.erp.content
com.company.erp.workeffort
com.company.erp.reporting
com.company.erp.identity
com.company.erp.legacyofbiz
```

Replace `company` with the actual organization namespace before implementation starts.

## Per-Service Hexagonal Architecture

Each service should follow a hexagonal/ports-and-adapters package layout.

```text
com.company.erp.orders
  domain
  application
    port
      in
      out
  adapter
    in
      web
      messaging
    out
      persistence
      servicebus
      legacyofbiz
      external
  config
```

Package responsibilities:

| Package | Responsibility |
| --- | --- |
| `domain` | Business entities, value objects, domain rules, domain services |
| `application` | Use cases, orchestration, transactions, application services |
| `application.port.in` | Inbound use case interfaces/commands/queries |
| `application.port.out` | Outbound dependencies required by application layer |
| `adapter.in.web` | REST controllers, request/response DTOs, OpenAPI integration |
| `adapter.in.messaging` | Service Bus consumers/event handlers |
| `adapter.out.persistence` | DB repositories, ORM/JDBC adapters, migrations integration |
| `adapter.out.servicebus` | Service Bus publishers |
| `adapter.out.legacyofbiz` | Calls to legacy adapter or OFBiz bridge, only when needed |
| `adapter.out.external` | External APIs: payment, email, tax, shipping, etc. |
| `config` | Framework configuration and dependency wiring |

## Allowed Per-Service Dependency Direction

Use this as the intended architecture inside each service:

```text
adapter.in.web -> application.port.in
adapter.in.messaging -> application.port.in
application -> domain
application -> application.port.out
adapter.out.persistence -> application.port.out
adapter.out.persistence -> domain
adapter.out.servicebus -> application.port.out
adapter.out.legacyofbiz -> application.port.out
adapter.out.external -> application.port.out
config -> all local service packages for wiring only
```

## Forbidden Per-Service Dependencies

These should become Sonar intended-architecture deviations or be covered by ArchUnit tests:

```text
domain -> application
domain -> adapter
domain -> config
domain -> framework packages such as Spring, Jersey, Azure SDK
application -> adapter
application -> config
adapter.in.web -> adapter.out.persistence
adapter.in.web -> adapter.out.servicebus
adapter.in.messaging -> adapter.out.persistence
adapter.out.* -> adapter.in.*
service A root package -> service B root package
```

## Cross-Service Dependency Rules

Direct source-code dependencies between services should be forbidden.

Allowed:

```text
services/order-service -> libs/contracts
services/order-service -> libs/platform
services/order-service tests -> libs/test-support
services/order-service -> generated OpenAPI/AsyncAPI client package if stored under libs/contracts or generated-clients
```

Forbidden:

```text
services/order-service -> services/accounting-service/src/main/java/...
services/order-service -> services/inventory-service/src/main/java/...
services/accounting-service -> services/order-service/src/main/java/...
```

If one service needs another service's data or behavior, use:

- Synchronous API through an explicitly generated client from OpenAPI contract.
- Asynchronous event through Azure Service Bus and AsyncAPI contract.
- Read model/projection owned by the consuming service.

## Shared Library Rules

Keep shared libraries small. Do not recreate a distributed monolith through shared domain libraries.

| Library | Allowed contents | Forbidden contents |
| --- | --- | --- |
| `libs/contracts` | OpenAPI specs, AsyncAPI specs, generated DTOs/clients, event envelopes | Business logic, persistence logic |
| `libs/platform` | Logging, tracing, auth helpers, error handling, health conventions | Domain rules, service-specific use cases |
| `libs/test-support` | Test containers setup, contract test helpers, fixtures | Production business logic |

Sonar intended relationships:

```text
services/* -> libs/contracts
services/* -> libs/platform
services/*/test -> libs/test-support
libs/contracts -> no services
libs/platform -> no services
libs/test-support -> libs/contracts + libs/platform only
```

## Legacy OFBiz Boundary

Only the `legacy-ofbiz-adapter` service may know about OFBiz-specific details.

Allowed:

```text
services/legacy-ofbiz-adapter -> legacy OFBiz APIs/client models
services/order-service -> legacy-ofbiz-adapter API client from libs/contracts
services/catalog-service -> legacy-ofbiz-adapter API client from libs/contracts
```

Forbidden:

```text
services/order-service -> org.apache.ofbiz.*
services/accounting-service -> org.apache.ofbiz.*
services/catalog-service -> org.apache.ofbiz.*
services/* -> legacy OFBiz database helper packages
```

The new services should not import OFBiz classes such as `GenericValue`, `GenericDelegator`, `EntityQuery`, OFBiz service dispatcher classes, OFBiz controller classes, or OFBiz entity model classes.

## Azure-Specific Code Rules

Azure SDK usage should be isolated to infrastructure adapters or platform libraries.

Allowed:

```text
adapter.out.servicebus -> Azure Service Bus SDK
adapter.out.blobstorage -> Azure Storage SDK
config -> Azure identity/config binding
libs/platform -> common Azure credential helper if needed
```

Forbidden:

```text
domain -> Azure SDK
application -> Azure SDK directly
domain -> Key Vault / Service Bus / Blob Storage / App Insights APIs
```

Use `DefaultAzureCredential` or equivalent in infrastructure/config code so local dev can use Azure CLI login and Azure deployment can use Managed Identity.

## Local Development Architecture Rules

Because the target app must run locally on MacBooks too, each service must support a `local` profile and a `cloud` profile.

Static code architecture should reflect this by isolating environment-specific implementations:

```text
adapter.out.servicebus.azure
adapter.out.servicebus.local
adapter.out.storage.azure
adapter.out.storage.local
adapter.out.email.mock
adapter.out.email.provider
```

Allowed:

```text
config -> local adapters
config -> cloud adapters
application -> application.port.out only
```

Forbidden:

```text
application -> adapter.out.servicebus.azure
application -> adapter.out.servicebus.local
domain -> local/cloud infrastructure packages
```

## Architecture Seed Requirement

SonarQube Cloud intended architecture is easiest to define after code containers exist. Before implementing business logic, create skeleton services and packages.

Minimum skeleton example for `order-service`:

```text
services/order-service/src/main/java/com/company/erp/orders/domain/Order.java
services/order-service/src/main/java/com/company/erp/orders/application/CreateOrderUseCase.java
services/order-service/src/main/java/com/company/erp/orders/application/port/in/CreateOrderCommand.java
services/order-service/src/main/java/com/company/erp/orders/application/port/out/OrderRepositoryPort.java
services/order-service/src/main/java/com/company/erp/orders/adapter/in/web/OrderController.java
services/order-service/src/main/java/com/company/erp/orders/adapter/in/messaging/OrderEventConsumer.java
services/order-service/src/main/java/com/company/erp/orders/adapter/out/persistence/OrderRepositoryAdapter.java
services/order-service/src/main/java/com/company/erp/orders/adapter/out/servicebus/OrderEventPublisherAdapter.java
services/order-service/src/main/java/com/company/erp/orders/config/OrderServiceConfig.java
```

After skeletons exist:

1. Run SonarQube Cloud analysis.
2. Open **Architecture > Current architecture** to inspect detected containers.
3. Open **Architecture > Intended architecture**.
4. Add top-level containers first: `services`, `libs`, `infra`, `migration`, legacy OFBiz.
5. Add service-level containers next.
6. Add per-service package containers after each service skeleton exists.
7. Define only allowed sibling relationships.
8. Save intended architecture.
9. Re-run analysis to raise deviations.

## Suggested Sonar Intended Architecture Modeling Order

Do not model everything at once. Build the model incrementally.

### Step 1: Top-Level Boundaries

Model:

```text
services
libs
infra
migration
legacy-ofbiz
```

Allowed relationships:

```text
services -> libs
migration -> services
migration -> legacy-ofbiz
```

### Step 2: Service Isolation

Model siblings under `services`:

```text
notification-service
product-catalog-service
party-service
inventory-service
order-service
accounting-service
legacy-ofbiz-adapter
```

Allowed relationships:

```text
services/* -> libs/contracts
services/* -> libs/platform
```

Do not allow service-to-service source dependencies.

### Step 3: Inside One Service

Start with one service, likely `notification-service` or `product-catalog-service`.

Model:

```text
domain
application
adapter
config
```

Allowed relationships:

```text
adapter -> application
application -> domain
config -> adapter
config -> application
config -> domain
```

### Step 4: Full Hexagonal Detail

Model:

```text
application.port.in
application.port.out
adapter.in.web
adapter.in.messaging
adapter.out.persistence
adapter.out.servicebus
adapter.out.external
adapter.out.legacyofbiz
```

Allowed relationships as listed in the per-service dependency direction section.

## Complementary Enforcement Outside Sonar

Use Sonar Architecture together with these controls:

| Concern | Enforcement |
| --- | --- |
| Package dependencies | Sonar Architecture + ArchUnit tests |
| Runtime API compatibility | OpenAPI + contract tests |
| Async event compatibility | AsyncAPI + schema validation |
| Azure topology | Terraform plan/policy checks |
| Secrets | Sonar secrets scan + Key Vault + CI secret scanning |
| Container security | Image scanning |
| Service ownership | CODEOWNERS + repo structure |
| DB ownership | Terraform DB modules + migration review |

## Example ArchUnit Rules

If using Java, add ArchUnit tests in each service to enforce critical rules before Sonar analysis.

Example rules to implement:

```text
domain packages must not depend on application, adapter, config, Spring, Azure SDK
application packages must not depend on adapter or config
adapter.in packages must not depend on adapter.out packages
services must not import packages from other services
only legacy-ofbiz-adapter may import org.apache.ofbiz packages
```

## Naming Checklist Before Implementation

Decide these before creating skeletons:

- Organization Java package prefix, e.g. `com.company.erp`.
- Exact service folder names under `services/`.
- Exact bounded context package names.
- Whether stack is Spring Boot, Quarkus, or .NET.
- Whether contracts live in `libs/contracts` or separate `contracts/` folder.
- Whether generated clients live under `libs/generated-clients` or inside `libs/contracts`.
- Naming convention for events, commands, DTOs, ports, adapters, and config classes.

## Recommended Class Naming Conventions

Class names are less important than package/folder names for Sonar Architecture, but consistent names help reviews and AI agents.

Suggested names:

| Type | Pattern | Example |
| --- | --- | --- |
| Domain entity | Noun | `Order` |
| Value object | Noun | `Money`, `OrderId` |
| Inbound port/use case | Verb phrase + `UseCase` | `CreateOrderUseCase` |
| Command | Verb phrase + `Command` | `CreateOrderCommand` |
| Query | Verb phrase + `Query` | `FindOrdersQuery` |
| Outbound port | Capability + `Port` | `OrderRepositoryPort` |
| Web adapter | Resource + `Controller` | `OrderController` |
| Persistence adapter | Resource + `RepositoryAdapter` | `OrderRepositoryAdapter` |
| Messaging consumer | Event/stream + `Consumer` | `OrderEventConsumer` |
| Messaging publisher | Event/stream + `PublisherAdapter` | `OrderEventPublisherAdapter` |
| Config | Service + `Config` | `OrderServiceConfig` |

## Final Recommendation

Create minimal service skeletons first, then configure SonarQube Cloud Intended Architecture from those detected containers.

Use Sonar to prevent static code architecture drift. Use Terraform, APIM policies, OpenAPI, AsyncAPI, contract tests, and integration tests to enforce Azure runtime architecture.


