---
name: post-intended-architecture
description: Explains what a project's "intended architecture" is in the sonarcloud-architecture service and how to create one programmatically by POSTing a Model. Use when asked to create, define, seed, or set up an intended architecture / architecture model for a project, or to understand what the intended architecture is composed of.
---

# Create an Intended Architecture (POST a Model)

## What the intended architecture is

The **intended architecture** is the user-defined *target* structure a project's code is
expected to conform to. The scanner compares the actual code against it and reports
violations. In this codebase it is composed of exactly two entities:

- **Model** — the core definition, one per project. Holds a `ModelData` tree of
  **perspectives → groups → constraints**. Creating/updating/deleting a Model is what
  fires the `IntendedArchitectureChanged` domain event, so **the Model _is_ the intended
  architecture**.
- **Patterns** — reusable sub-structures. A group may reference a `patternId`; the pattern's
  groups and constraints are resolved and injected into the intended model at read time
  (only for orgs with the extension pack). Patterns *compose* the intended architecture but
  do not fire the change event on their own.

Directives and boundary descriptors are **separate** project-configuration inputs — they are
NOT part of the intended architecture. To create an intended architecture, you POST a Model.

Source of truth:
- Concept: `.../core/business/intended/service/IntendedArchitectureService.java`
- Endpoint: `visualization/visualization-lambda/.../api/web/ModelsController.java`
- Schema: `visualization/visualization-platform/api-definitions/openapi.yaml`

## The endpoint

`POST /architecture/models` — creates a new Model for a project.

- Effective path is `/architecture/models` (the controller is `@Controller("/architecture")`;
  the OpenAPI spec declares the path as `/models` and API Gateway adds the `/architecture`
  stage prefix).
- Related writes: `DELETE /architecture/models/{id}` (remove). `PATCH /architecture/models/{id}`
  exists in the API but is **broken on this deployment** — update via DELETE + POST (see step 7).

## Preferred client: `sonar api` (no token handling)

**Use the `sonar` CLI's `api` command for ALL requests** — reads and writes. It authenticates
automatically (no bearer token, no `$SONARQUBE_TOKEN`, no base URL to figure out) and
auto-routes V2 paths to the API host, so you pass the path unchanged:

```bash
sonar api <get|post|patch|delete> "<endpoint>" [--data '<json-string>']
```

- The endpoint path starts with `/` and may include query params. For these architecture
  endpoints use `/architecture/...` and `/projects/...` directly (the CLI adds the
  `api.sonarcloud.io` host + any V2 routing itself).
- `--data` takes a JSON **string**, not `@file`. For a large body, pass
  `--data "$(cat body.json)"`.
- `-v` prints the full request/response for debugging.

Only fall back to raw `curl` (with a bearer `$SONARQUBE_TOKEN`) if the `sonar` CLI is
unavailable. The curl equivalents are noted where relevant below.

## Authentication (only relevant for the curl fallback)

`sonar api` handles auth for you — skip this section unless you must use curl. External
endpoints are protected by an API Gateway **Lambda Authorizer (JWT)**; a curl client must send
a bearer JWT (`Authorization: Bearer <JWT>`, `Content-Type: application/json`). The controller
also enforces **project-scoped authorization**: the identity must have edit-architecture
permission on the target `projectId` (else `403`). Org scoping is computed internally from
`projectId`. Do not fabricate credentials; ask the user how to obtain a token if needed.

## Resolving `projectId` and `organizationId` (UUIDs)

The `projectId` field requires the project's **UUID**, not its human-readable key. Resolve it
(and the org UUID) at runtime from the project **key** with a single `sonar api` call — no
token, no separate org lookup, no `resolve-project-id.sh` script needed:

```bash
sonar api get "/projects/projects?keys=<project-key>&pageIndex=1&pageSize=50" \
  | jq -r '.projects[0] | .id, .organizationId'
# .projects[0].id            -> projectId UUID
# .projects[0].organizationId -> organizationId UUID
```

Use `keys=` (plural) with `pageIndex`/`pageSize` — `query=` returns `400` on this endpoint.

> curl fallback (needs `$SONARQUBE_TOKEN`): the bundled
> `scripts/resolve-project-id.sh <project-key>` validates the token then GETs the same
> `/projects/projects` endpoint and prints the UUID. Prefer the `sonar api` call above.

## Request body

Send the structured `model` object (the deprecated `data` string form must NOT be used).

Required:
- `projectId` (uuid) — the only field actually read server-side.
- `model.perspectives` — at least one perspective.
- Each perspective requires `qualifiers`, `language`, and `constraints` (may be `[]`, but the
  key must be present).
- Each group requires `label`. Each constraint requires `from` and `to`.

Notes:
- `organizationId` is nominally deprecated (computed from `projectId`), but this deployment
  has been observed to **require it in the body** — include it to be safe (it is echoed back
  on GET, so you can copy it from an existing model).
- Enums: `qualifiers` ∈ `{ file, namespace }`; `language` ∈ `{ java, js, ts, py, cs }`.
- A group either lists inline sub-`groups` OR references a shared `patternId` (uuid) — not both.
- `constraints[].from` / `to` reference group `label`s — bare label for top-level siblings, or a
  `parent/child` path for nested siblings. Constraints are **sibling-only** and must all live in
  the perspective-level `constraints` array. See *Constraints and interfaces* below.

### Field reference

| Path | Type | Required | Notes |
|---|---|---|---|
| `projectId` | uuid | yes | target project |
| `organizationId` | uuid | (schema) | deprecated / ignored |
| `model.perspectives[]` | array | yes | one per language/qualifier view |
| `perspectives[].label` | string | no | display name |
| `perspectives[].description` | string | no | |
| `perspectives[].qualifiers` | enum | yes | `file` \| `namespace` |
| `perspectives[].language` | enum | yes | `java` \| `js` \| `ts` \| `py` \| `cs` |
| `perspectives[].groups[]` | array | no | recursive `ModelGroup` |
| `perspectives[].constraints[]` | array | yes | allowed `from`→`to` deps |
| `groups[].label` | string | yes | identifier + display name |
| `groups[].description` | string | no | |
| `groups[].patterns[]` | string[] | no | file/namespace globs for components |
| `groups[].groups[]` | array | no | nested subgroups |
| `groups[].interface` | boolean | no | marks a CHILD group as part of its parent's public interface (externally referenceable). Inert unless the parent has `interfaceAccess: true`. See *Constraints and interfaces*. |
| `groups[].interfaceAccess` | boolean | no | set on a PARENT group to ENFORCE interface access — only children with `interface: true` may be referenced from outside the parent. This is the switch that activates the child `interface` flags. See *Constraints and interfaces*. |
| `groups[].patternId` | uuid | no | use shared pattern instead of inline `groups` |
| `constraints[].from` | string | yes | source group label (or `parent/child` path for a nested group) |
| `constraints[].to` | string | yes | target group label (or `parent/child` path for a nested group) |

## Constraints and interfaces (nesting, sibling rule, visibility)

This is the part most people (and the schema) get wrong. The OpenAPI schema *allows*
nested `groups[].constraints`, but this deployment does NOT honor them — get the rules
below right or constraints are silently dropped and enforcement is weaker than it looks.

### 1. All constraints live in the perspective-level `constraints` array

Do **not** put a `constraints` array inside a group object. Constraints nested inside a
group POST fine (`201`) but are **silently discarded** — read the model back and they are
gone. Put *every* constraint (top-level and intra-container) in the single
`perspectives[].constraints` array.

### 2. Constraints are only ever between SIBLINGS

A constraint's `from` and `to` must be two groups that share the same parent. You cannot
write a constraint from a group to its own nephew/cousin (a child of a different parent).

- **Top-level siblings** are addressed by bare label: `{ "from": "billing", "to": "common" }`.
- **Nested siblings** are addressed by a `parent/child` PATH label:
  `{ "from": "billing/billing-api", "to": "billing/billing-domain" }`.
  (The separator is `/` regardless of language.)

### 3. Cross-container edges must be modeled parent-to-parent, then gated by interfaces

Because constraints are sibling-only, you **cannot** express "gateway's client code may
call the users service's api package" as `{ from: "gateway/gateway-clients", to:
"users/users-api" }` — those are not siblings. Model it as a **parent-to-parent** edge
between the top-level containers instead:

```json
{ "from": "api-gateway", "to": "users-service" }
```

Then control *what inside `users-service` is reachable* with interfaces:

- Put `"interfaceAccess": true` on the **parent** container (`users-service`). This turns
  on enforcement: only children flagged as interfaces are referenceable from outside.
- Put `"interface": true` on each **child** that should be public (e.g. `users-api`).
  Leave every internal child (`users-domain`, `users-repository`, `users-events`) without
  it (or `false`).

Net effect: `api-gateway → users-service` resolves **only onto `users-api`**; any code
reaching into `users-domain`/`repository`/`events` from outside is a violation.

### 4. `interfaceAccess` is the switch — without it, `interface` flags are inert

If a parent does **not** have `interfaceAccess: true`, the server treats ALL of its
children as externally referenceable (it defaults untouched children to `interface: true`
on save, ignoring any `interface: false` you sent). Symptom: "interfaces aren't used
anywhere / the boundary isn't enforced." Fix: set `interfaceAccess: true` on every
container whose internals must stay private.

A container with `interfaceAccess: true` and **no** child marked `interface: true` exposes
nothing externally — correct for a pure consumer (e.g. an api-gateway that nothing depends
on).

### 5. Always verify by reading the model back

After POST, GET the model and diff the stored `constraints` count and the
`interface`/`interfaceAccess` flags against what you sent. Silent drops (nested
constraints) and silent flips (`interface` defaulting to true) only show up on read-back.

### 6. Every group with nested children needs its OWN `patterns` too

A parent group that has `groups[]` but no `patterns` of its own **loads inconsistently** —
symptom observed: SonarQube Cloud's UI generically fails with "The component cannot be
loaded" even though the POST returns `201` and the GET echoes the data back unchanged (no
backend validation catches this — it is purely a downstream rendering/graph-matching
failure). The one confirmed-working fixture in this codebase
(`visualization/visualization-lambda/src/test/resources/aoc/model/model.json`) has **zero
exceptions** to this rule: every container node, all the way up the tree, carries its own
`patterns` array as the umbrella superset of its children's patterns.

Fix: for every group that has `groups[]`, set its own `patterns` to the union of all its
children's `patterns` (recursively, if more than one level deep). Do this even though the
schema marks `patterns` optional and nothing server-side requires it.

```json
{
  "label": "billing",
  "patterns": ["com.example.billing.api.**", "com.example.billing.internal.**"],
  "groups": [
    { "label": "billing-api",      "interface": true,  "patterns": ["com.example.billing.api.**"] },
    { "label": "billing-internal", "interface": false, "patterns": ["com.example.billing.internal.**"] }
  ]
}
```

### 7. An `-api`/`-internal` (facade/impl) split needs its OWN intra-container constraint

Splitting a container into a public facade group (`interface: true`) and a private
implementation group (`interface: false`) controls what **other** containers can reach
(§3–4). It does **not** implicitly allow the facade to call its own implementation — with
no constraint between them, `billing-api → billing-internal` is itself a violation, which
defeats the purpose of having a facade at all. Add the sibling constraint explicitly (path
form, since they're nested siblings — see §2), one-directional (facade depends on impl,
never the reverse, to avoid modeling a cycle):

```json
{ "from": "billing/billing-api", "to": "billing/billing-internal" }
```

Do this for every container that has both an `-api`/public child and an `-internal`/private
child. It is easy to add the `interfaceAccess`/`interface` flags (§3–4) and stop there,
forgetting this constraint — the model will still POST and load fine, so the omission is
silent until someone notices the facade "can't" call its own implementation.

### 8. Don't leave `interface` unset on a private child — set it to `false` explicitly

Per §4, an untouched child defaults to `interface: true` (public) once its parent has
`interfaceAccess: true`. In practice "untouched" includes a child where you never wrote the
`interface` key at all (`null`), not just one you explicitly set to `false` — so omitting
the key on an internal child does **not** reliably make it private; write
`"interface": false` on every internal/private child explicitly. Verify on read-back (§5)
that it comes back `false`, not `null` or `true`.

### 9. Sibling `patterns` overlap (bare or wildcarded) does NOT cause a load failure — tested and disproven

An earlier version of this section speculated that overlapping sibling `patterns` —
including a `.**` wildcard on one sibling covering a package another sibling also
claims — could be the cause of a "component cannot be loaded" failure. This was tested
directly and **disproven**: `order-api`'s patterns were deliberately changed to
`["org.apache.ofbiz.order.**", "org.apache.ofbiz.accounting.**"]` — full wildcards that
textually cover every one of `order-internal`'s 15 sibling sub-package patterns — and the
model still POSTed and loaded cleanly in the SonarQube Cloud UI with no error.

**Do not spend time scripting a pattern-overlap checker before POSTing.** Sibling
`patterns` overlapping, bare-vs-bare or with `.**` wildcards, is not what breaks a model.
The confirmed actual root cause of the load failure is §6 (a parent group with nested
`groups[]` but no `patterns` of its own) — that is the one fix that took a broken model to
a working one in practice. If a model still fails to load in the UI after a `201`/clean
read-back, re-check §6 first, not pattern overlap.

## Minimal example

One perspective, two groups, one allowed dependency (`api` → `core`). Resolve the UUIDs, build
the body with `jq` (avoids fragile quoting), then POST it via `sonar api`:

```bash
read PROJECT_ID ORG_ID < <(sonar api get \
  "/projects/projects?keys=<project-key>&pageIndex=1&pageSize=50" \
  | jq -r '.projects[0] | "\(.id) \(.organizationId)"')

jq -n --arg projectId "$PROJECT_ID" --arg orgId "$ORG_ID" '{
  projectId: $projectId,
  organizationId: $orgId,
  model: {
    perspectives: [
      {
        label: "Layered architecture",
        description: "Intended layering for the service",
        qualifiers: "file",
        language: "java",
        groups: [
          { label: "api",  description: "API / web layer",   patterns: ["**/api/**"] },
          { label: "core", description: "Business/core layer", patterns: ["**/core/**"] }
        ],
        constraints: [
          { from: "api", to: "core" }
        ]
      }
    ]
  }
}' > /tmp/model-body.json

sonar api post "/architecture/models" --data "$(cat /tmp/model-body.json)"
```

Success returns **`201 Created`** with the `Model` object: a generated `id` (uuid),
`projectId`, `organizationId`, and the echoed `model`.

> curl fallback: `... | curl -sS -X POST "https://api.sonarcloud.io/architecture/models"
> -H "Authorization: Bearer $SONARQUBE_TOKEN" -H "Content-Type: application/json"
> --data-binary @-`

## Steps for Claude to create one

1. Ensure the `sonar` CLI is available (it handles auth). Get the project **key** from the user.
2. Resolve the **`projectId`** and **`organizationId`** UUIDs from the key with one call:
   `sonar api get "/projects/projects?keys=<key>&pageIndex=1&pageSize=50"` (see *Resolving* above).
3. Build the `model` payload: pick `language` + `qualifiers`, define `groups` (with `patterns`
   matching the code layout), and list allowed dependencies in `constraints`. Read *Constraints
   and interfaces* first — constraints are sibling-only, all live in the perspective-level array,
   and cross-container boundaries need `interfaceAccess` + `interface`. In particular:
   - Give every container that has `groups[]` its own `patterns` too (union of its children's) — §6.
     This is the #1 confirmed cause of a model that POSTs fine but won't load in the UI.
   - If a container splits into `-api`/`-internal`, add the intra-container `-api → -internal`
     constraint explicitly (§7) and set `"interface": false` explicitly on the internal child (§8).
   - Sibling `patterns` overlapping (bare or `.**`-wildcarded) is NOT a problem — tested and
     disproven, see §9. Don't spend time checking for it.
4. POST with `sonar api post "/architecture/models" --data "$(cat body.json)"`. Expect `201`.
5. **Read the model back** (`sonar api get "/architecture/models/<id>"`) and verify the stored
   constraint count and interface flags match what you sent (see *Constraints and interfaces* §5).
   A `201` only proves the write was accepted, not that the UI can render/load it — if the user
   reports the model won't load in SonarQube Cloud after a successful POST, suspect §6 first (a
   parent group missing its own `patterns`) since it isn't caught by backend validation or a naive
   read-back diff.
6. On `403`, the identity lacks `canEditArchitecture` for that project. On `400`, check that
   exactly one of `model`/`data` is set, that required fields/enums are valid, and that no
   constraint is nested inside a group (see *Constraints and interfaces*).
7. To change an existing model: **PATCH is broken on this deployment** — it returns
   `400 {"message":"Internal Server error"}` for any body, even echoing the model's own
   unchanged `model` back. Update by **DELETE then POST** instead:
   a. `sonar api get "/architecture/models?projectId=<uuid>"` → take `.[0].id`.
   b. Back up: `sonar api get "/architecture/models/<id>" > backup.json` in case you must restore.
   c. `sonar api delete "/architecture/models/<id>"` (succeeds silently; verify the list is empty).
   d. `sonar api post "/architecture/models" --data "$(cat body.json)"` (expect `201`).
   Round-trip is loss-free: a DELETE+POST of a model's own `{projectId, organizationId, model}`
   reproduces it byte-for-byte (verified).

## Gotchas

- Don't send both `model` and `data` — the server requires exactly one, and `data` is deprecated.
- `constraints` is required per perspective; include `[]` if there are genuinely none.
- One project has at most one Model — POST creates; if it already exists, **DELETE then POST**
  (PATCH is broken — see step 7). A second POST while a model exists conflicts.
- **PATCH is unusable** on this deployment: `400 {"message":"Internal Server error"}` for any
  body. Always update via DELETE + POST.
- **Never nest a `constraints` array inside a group** — it POSTs but is silently dropped. All
  constraints go in the perspective-level `constraints` array (see *Constraints and interfaces*).
- **Constraints are sibling-only.** Cross-container edges must be parent-to-parent and gated by
  `interfaceAccess` (parent) + `interface` (child) — not written as child→child.
- **`interface` flags are inert without `interfaceAccess: true` on the parent**; the server
  defaults untouched children to `interface: true` (all public). Set `interfaceAccess` to enforce.
- **Always read the model back after writing** to catch silently dropped constraints and flipped
  interface flags — a `201` does not mean everything you sent was kept.
- `organizationId` may be required in the body on this deployment despite the schema calling it
  deprecated — include it (it's echoed back on GET).
- The `/models` GET reads are anonymous, but writes require an authenticated, authorized token.
- **A container with `groups[]` needs its own `patterns` too** (union of its children's) — omitting
  it POSTs fine and reads back fine, but can make the model fail to load in SonarQube Cloud's UI
  ("The component cannot be loaded") with no error pointing at the cause. See §6.
- **An `-api`/`-internal` split needs an explicit intra-container constraint** (`parent/api →
  parent/internal`) or the facade can't legitimately depend on its own implementation. See §7.
- **Set `"interface": false` explicitly on internal/private children** — an omitted/`null`
  `interface` key defaults to `true` (public) once the parent has `interfaceAccess: true`, same as
  an explicit `false` you forgot to send. See §8.
- **Sibling `patterns` overlapping does NOT break the model** — tested directly (a sibling's
  `.**` wildcard covering another sibling's exact sub-packages POSTed and loaded fine in the UI).
  Don't script an overlap checker; it's not a real failure mode here. See §9.
