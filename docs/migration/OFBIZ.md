# Apache OFBiz architecture and repository structure

## Executive summary

Apache OFBiz is a Java-based enterprise application suite and application-development framework. This repository is best described as a **modular monolith**: accounting, order management, product, party, manufacturing, marketing, content, human resources, shipment, and work-effort capabilities are separated into components, but they normally run together in one OFBiz runtime and share the same entity and service infrastructure.

It is not a microservices system in its present form. The modules have substantial in-process coupling to common framework packages, particularly the entity engine, service engine, web application framework, security layer, and base utilities. OFBiz does expose remote integration mechanisms, but those do not make its business components independently deployable services.

The implementation is metadata-driven. XML definitions and component descriptors assemble entities, services, screens, forms, request routes, and event-condition-action rules around Java and Groovy implementation code.

## High-level runtime

```text
Browser / API client / external system
                  |
      Servlet MVC and REST/OpenAPI
                  |
        OFBiz service dispatcher
       /          |             \
 Java/Groovy   async jobs     ECA rules
 services       and JMS       and workflows
       \          |             /
             Entity engine
                  |
            JDBC database
```

The principal layers are:

1. **Presentation and transport** — servlet request routing, server-rendered screens and forms, FreeMarker templates, REST resources, and legacy remote protocols.
2. **Application/service layer** — named services invoked through `LocalDispatcher`/`ServiceDispatcher`, with validation, authorization, transaction, synchronous, asynchronous, scheduled, and remote execution facilities.
3. **Domain components** — ERP/business capabilities under `applications/`, implemented as services, events, entity definitions, UI metadata, and supporting Java code.
4. **Persistence** — OFBiz's own Entity Engine, based on `Delegator`, `GenericValue`, entity metadata, queries, relations, and JDBC-backed data sources.
5. **Runtime platform** — component loading, container lifecycle, embedded Tomcat/Catalina, configuration, security, logging, caching, and testing support.

## Repository organization

| Area | Responsibility |
| --- | --- |
| `framework/` | Reusable OFBiz platform: bootstrap, component container, entity engine, service engine, web framework, widgets, security, REST API, testing, and utilities. |
| `applications/` | Business modules such as accounting, order, party, product, manufacturing, marketing, content, human resources, shipment, security extensions, and work effort. |
| `themes/` | Shared theme infrastructure and selectable server-rendered UI themes. |
| `plugins/` | Extension point for optional or locally developed OFBiz components. |
| `gradle/`, `buildSrc/`, Gradle files | Build logic and dependency management. |
| `docker/`, `DOCKER.adoc` | Container-oriented deployment support and database-specific configuration templates. |
| `runtime/` | Runtime state and generated/deployed data rather than primary product source. |

An OFBiz component is a coarse-grained module described by `ofbiz-component.xml`. A component can contribute entity models, service definitions, data, web applications, tests, and classpath resources. This plugin-like component model is the main unit of modularity.

## Framework and technology choices

### Core platform

- **Java and Jakarta APIs** form the main implementation platform.
- **Gradle** is the build system, with shared build logic in `buildSrc` and centralized dependency declarations.
- **Embedded Apache Tomcat/Catalina** supplies the servlet container. The `CatalinaContainer` and OFBiz container lifecycle start it as part of the application runtime.
- **XML configuration and metadata** are first-class architectural inputs rather than incidental configuration.
- **Groovy and scripting support** allow service and business-rule implementations alongside Java. The service engine also contains pluggable execution engines.

This is primarily a custom OFBiz framework, not a Spring/Spring Boot application. Dependency access and application assembly revolve around OFBiz containers, factories, dispatchers, request context objects, and XML metadata rather than annotation-driven Spring dependency injection.

### Persistence and database

OFBiz uses its own active-record/data-mapper-like **Entity Engine**, not JPA/Hibernate as its central persistence abstraction.

Important concepts include:

- `Delegator` and `GenericDelegator` as the persistence façade;
- `GenericValue` as the generic entity-record representation;
- `EntityQuery` and entity-condition APIs for querying;
- XML entity definitions, fields, primary keys, and relationships;
- view entities for metadata-defined joins and projections;
- transaction and XA-related infrastructure;
- seed, demo, and migration data loaded through framework facilities.

The database is configurable rather than hard-wired. JDBC-backed relational databases are the intended storage model; the repository includes deployment/configuration support for PostgreSQL. Database selection and connection details are environment/configuration concerns, so a particular database should not be inferred as mandatory from the codebase.

### Service and business-logic layer

The **Service Engine** is the architectural center of business execution. Business operations are registered as named services and invoked through a dispatcher rather than by directly constructing service classes. Service definitions provide typed inputs/outputs and can attach authentication, authorization, validation, transaction, semaphore, and execution policies.

Supported patterns visible in the service architecture include:

- synchronous and asynchronous dispatch;
- persisted and scheduled jobs managed by `JobManager`;
- Java and Groovy service engines;
- automatic entity CRUD services;
- grouped and routed services;
- transaction-aware execution and callbacks;
- remote invocation through REST, SOAP, RMI, and JMS-related engines;
- service-level ECA rules.

Many classes named `*Services` are stateless collections of operations accepting a `DispatchContext` and a parameter map. This is closer to a transaction-script/service-layer style than rich domain objects or hexagonal aggregates.

### Event-driven behavior and messaging

OFBiz has an internal **event-condition-action (ECA)** model:

- Entity ECA rules react to persistence operations.
- Service ECA rules react around service execution.
- Mail/MCA rules process incoming messages.

The service framework also includes JMS listeners, queue/topic support, and a JMS service engine. This means OFBiz can integrate with a JMS provider, but the repository does not establish a mandatory Kafka, RabbitMQ, or ActiveMQ broker as a core deployment component. In-process dispatch and database-persisted jobs are more fundamental to the default architecture than an external message broker.

### Backend web architecture

The traditional web stack uses a **front controller/MVC** pattern:

- `ControlServlet` is the web entry point.
- XML controller files map request names to events and responses.
- event handlers adapt HTTP requests to Java, service, script, SOAP, or other handlers.
- view handlers select screen, FreeMarker, JSP, HTTP, or other renderers.
- filters and framework utilities provide authentication, CSRF/token handling, request context, sessions, security headers, and statistics.

This request/event/view design is configuration-driven and predates modern annotation-heavy MVC frameworks, but plays a similar architectural role.

### Frontend and UI

The built-in user interface is predominantly **server rendered**, not a standalone SPA frontend:

- OFBiz Screen and Widget metadata define screens, forms, menus, and trees.
- FreeMarker renders templates and supplies OFBiz-specific transforms.
- themes under `themes/` provide presentation variants and common assets.
- JavaScript and CSS enhance pages, but the primary composition and routing remain server-side.
- JSP remains available through a view handler, although FreeMarker/widgets are the more characteristic OFBiz approach.

No separately deployed React, Angular, or Vue frontend is evident as the core UI architecture. Such a client could instead consume the REST API.

### APIs and integrations

The repository contains a REST framework under `framework/rest-api` using **Jakarta REST/JAX-RS concepts** and Swagger/OpenAPI models. It includes authentication resources and filters, CORS handling, HTTP Basic support, request processing, and generated OpenAPI descriptions tied to OFBiz service metadata.

Other integration facilities visible in the framework include:

- SOAP/WSDL service support;
- RMI remote dispatch;
- JMS queues and topics;
- SMTP/email through Jakarta Mail, including OAuth-related SMTP support;
- WebDAV and iCalendar-related functionality;
- FTP/content and payment/shipping integrations in business modules.

These are integration adapters around the modular monolith, not separate deployable services in this repository.

### Security

Security is centralized in framework and security-extension components. It includes:

- user login and session-based web authentication;
- permission checks through OFBiz security abstractions;
- service authentication/authorization metadata;
- LDAP authentication services;
- REST authentication filters and token-related controls;
- CSRF, CORS, security-header, and login-event handling;
- application-specific permissions tied to users, roles, and business data.

Because business services and views rely on shared framework security, security is cross-cutting rather than independently owned by each business module.

### Testing

The repository contains unit and integration-style tests across framework and application modules. Its test infrastructure includes JUnit Jupiter support plus compatibility infrastructure for older JUnit suites. Test tools can inject OFBiz runtime facilities such as a `Delegator` and `LocalDispatcher`, reflecting the fact that many meaningful tests require the OFBiz container and metadata context.

## Business components

The application layer covers a broad ERP domain:

- **Accounting** — invoices, payments, financial accounts, agreements, tax, periods, and general ledger.
- **Order** — sales and purchase order lifecycle and related fulfillment processes.
- **Party** — people, organizations, roles, relationships, contact data, and communications.
- **Product** — catalog, category, pricing, promotion, inventory, facilities, suppliers, configuration, and subscriptions.
- **Manufacturing** — bills of material, routing, MRP, job-shop management, and technical data.
- **Shipment** — packing, picking, verification, carrier/shipping processes, and package weights.
- **Content** — content/data resources, CMS features, templates, surveys, blogs, and output generation.
- **Marketing and SFA** — campaigns, tracking, reports, and sales-force features.
- **Human resources and work effort** — organizational work, calendars, tasks, and related records.
- **Security extensions and common extensions** — application-level login, certificates, migration, shared setup, and supporting features.

The domain boundaries are recognizable, but dependencies are not strictly isolated. For example, accounting can depend on order, party, product, entity, security, service, and common packages. This reflects an integrated ERP data model rather than bounded contexts with independent databases.

## Architectural patterns

The strongest patterns are:

- **Modular monolith / component architecture** — coarse modules assembled into one runtime.
- **Layered architecture** — web/UI, services, entities, and infrastructure layers.
- **Front Controller and MVC** — centralized servlet routing to events and views.
- **Service Layer / Transaction Script** — named operations dispatched through a common engine.
- **Metadata-driven programming** — XML models and mappings define large portions of behavior.
- **Data Mapper / generic record model** — the Entity Engine maps metadata-defined entities through generic APIs.
- **Plugin architecture** — components and themes contribute resources through descriptors.
- **Event-condition-action rules** — declarative hooks around entity, service, and mail events.
- **Strategy/factory patterns** — replaceable service engines, event handlers, view handlers, security implementations, and container types.
- **Template View** — FreeMarker and widget rendering for server-side UI.
- **Scheduler and durable job queue** — asynchronous and scheduled service execution.

It is less aligned with domain-driven design, ports-and-adapters, or independently owned microservices. Shared generic maps/records and broad framework dependencies favor flexibility and metadata reuse over compile-time domain typing and strict boundary enforcement.

## Deployment topology

A typical deployment consists of:

- one or more identical OFBiz JVM instances;
- embedded Tomcat serving administrative and business web applications plus APIs;
- a shared relational database;
- external SMTP, payment, shipping, directory, or other systems as required;
- optionally a JMS provider or remote-service endpoints for integrations;
- a reverse proxy/load balancer and externalized secrets/configuration in production.

Scaling is principally replication of the whole application tier against shared infrastructure. Splitting individual business components into independently deployed services would require explicit modernization work: extracting contracts, removing in-process dependencies, assigning data ownership, and adding reliable inter-service communication.

## Dependency shape and architectural observations

Sonar's Java architecture graph reported **71 top-level package nodes and 399 package dependencies**. The most pervasive shared packages are `org.apache.ofbiz.entity`, `org.apache.ofbiz.service`, `org.apache.ofbiz.webapp`, `org.apache.ofbiz.security`, and `org.apache.ofbiz.base.*`. The service subtree itself contains dispatch, engine, ECA, job, JMS, mail, RMI, semaphore, and tracking packages.

This dependency shape has several consequences:

- Framework changes can have wide impact across business applications.
- Component boundaries organize ownership and packaging but are not hard isolation boundaries.
- Shared transactions and direct entity access make cross-domain workflows straightforward.
- Independent deployment and per-domain technology choices are difficult.
- XML metadata is as important to change impact as Java call graphs.

No intended-architecture constraints are currently configured in Sonar for this project; the intended model returned an empty constraint set. The descriptions above therefore characterize the observed implementation rather than compliance with an enforced target architecture.

## Practical mental model

For a new contributor, the shortest useful mental model is:

1. A component registers its entities, services, data, web applications, and resources.
2. A web controller or API receives a request and invokes an event or named service.
3. The service dispatcher establishes execution policy and calls Java, Groovy, entity-auto, async, or remote machinery.
4. Business code reads and writes metadata-defined entities through a `Delegator`.
5. ECA rules and scheduled jobs add side effects and deferred processing.
6. A widget/FreeMarker view renders HTML, or the API serializes a response.

That combination—integrated ERP modules on a shared, metadata-driven Java runtime—is the defining architectural characteristic of OFBiz.

## Analysis basis and limitations

This document was derived from Sonar architecture graphs and semantic navigation over the repository, including package dependencies, service/entity/web/container symbols, REST/OpenAPI resources, FreeMarker/widget code, ECA and job infrastructure, security, testing, and integration adapters. Repository paths and filenames were also used to describe the top-level layout.

`framework/entity/config/entityengine.xml` was intentionally excluded from direct inspection at the user's request after a secrets scan flagged a credential-like value. Later direct file inspection was also avoided when the deterministic secrets scanner could not access the local keychain. Consequently, database configuration is described at the architectural level and no credential, environment-specific connection, or unverified default database setting is reproduced here.
