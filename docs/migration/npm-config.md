# Private registry configuration for local and container builds

This guide describes how to build the OFBiz hybrid stack without direct access to public package or container registries. It uses the machine's existing Artifactory configuration while ensuring credentials are never committed or stored in container image layers.

## Current local configuration

The effective npm registry on the development machine is:

```text
https://repox.jfrog.io/artifactory/api/npm/npm/
```

The user-level `~/.npmrc` also provides an Artifactory authentication token and corporate TLS settings. Treat that file as a secret. Do not copy it into the repository, add it to a Docker build context, print it in build logs, or bake it into an image.

## Required outcome

A compliant build must:

- resolve npm packages exclusively through Artifactory;
- resolve Maven and Gradle plugins exclusively through approved Artifactory repositories;
- pull base images from the approved internal container registry;
- trust the corporate inspection CA explicitly;
- inject tokens only as runtime or BuildKit secrets;
- leave no token in Git, image layers, build arguments, environment dumps, or generated files;
- work from a fresh checkout without relying on pre-existing host `node_modules` directories.

## 1. Verify the host npm configuration

Use commands that do not display the token:

```sh
npm config get registry
npm config get userconfig
npm ping
```

Expected registry:

```text
https://repox.jfrog.io/artifactory/api/npm/npm/
```

Confirm that the user configuration contains entries for the registry host, `_authToken`, and the corporate CA. Never paste the token into a shell command, Docker build argument, Compose environment variable, or CI log.

## 2. Install npm dependencies in Docker with BuildKit secrets

Use a dedicated asset-build stage. Mount `.npmrc` and the corporate CA as secrets for the single command that needs them:

```dockerfile
# syntax=docker/dockerfile:1
FROM <internal-container-registry>/node:22-alpine AS theme-assets

WORKDIR /assets
COPY themes/common-theme/webapp/common-theme/js/package.json ./
COPY themes/common-theme/webapp/common-theme/js/package-lock.json ./

RUN --mount=type=secret,id=npmrc,target=/root/.npmrc,required=true \
    --mount=type=secret,id=corporate_ca,target=/run/secrets/corporate-ca.pem,required=true \
    NODE_EXTRA_CA_CERTS=/run/secrets/corporate-ca.pem \
    npm ci --ignore-scripts
```

Copy only the installed assets into the OFBiz runtime or an intermediate OFBiz build stage:

```dockerfile
COPY --from=theme-assets /assets/node_modules \
    /ofbiz/themes/common-theme/webapp/common-theme/js/node_modules
```

Do not use either of these patterns:

```dockerfile
COPY .npmrc /root/.npmrc
ARG NPM_TOKEN
```

Both risk persisting credentials in the image history or build metadata.

## 3. Supply secrets from Docker Compose

Compose build secrets can reference the existing machine-local files:

```yaml
services:
  legacy-ofbiz-app:
    build:
      context: ..
      secrets:
        - npmrc
        - corporate_ca

secrets:
  npmrc:
    file: ${NPM_CONFIG_USERCONFIG:-${HOME}/.npmrc}
  corporate_ca:
    file: ${CORPORATE_CA_FILE:-${HOME}/.certs/Sonar-CloudFlare-Inspection-Cert.pem}
```

Some Compose versions do not expand nested defaults consistently. If that applies, define explicit paths before starting the stack:

```sh
export NPM_CONFIG_USERCONFIG="$HOME/.npmrc"
export CORPORATE_CA_FILE="$HOME/.certs/Sonar-CloudFlare-Inspection-Cert.pem"
docker compose -p erp-local -f local-dev/docker-compose.yml up -d --build
```

The files remain outside the build context and are exposed only to the relevant `RUN` instruction.

## 4. Prefer CA trust over disabling TLS verification

The local npm configuration currently includes TLS-related settings. Container builds should install or reference the corporate CA and keep TLS verification enabled whenever possible.

For Node.js, set:

```sh
NODE_EXTRA_CA_CERTS=/run/secrets/corporate-ca.pem
```

For Alpine-based images, the CA can instead be installed into a temporary build stage's trust store. Avoid committing the certificate unless organizational policy explicitly classifies it as public and distributable. Never disable TLS validation merely to make the build pass.

## 5. Configure Gradle and Maven through Artifactory

Changing npm alone is insufficient. The Spring Boot service currently needs Gradle plugins and Maven artifacts. Configure both plugin and dependency resolution to use approved Artifactory virtual repositories.

Use a machine- or CI-provided Gradle init script, mounted as a BuildKit secret, so repository credentials remain outside the project:

```groovy
settingsEvaluated { settings ->
    settings.pluginManagement.repositories.clear()
    settings.pluginManagement.repositories.maven {
        url = uri(System.getenv('ARTIFACTORY_GRADLE_PLUGIN_URL'))
        credentials {
            username = System.getenv('ARTIFACTORY_USER')
            password = System.getenv('ARTIFACTORY_TOKEN')
        }
    }
}

allprojects {
    repositories.clear()
    repositories.maven {
        url = uri(System.getenv('ARTIFACTORY_MAVEN_URL'))
        credentials {
            username = System.getenv('ARTIFACTORY_USER')
            password = System.getenv('ARTIFACTORY_TOKEN')
        }
    }
}
```

Prefer secret files over environment variables when the build platform supports them. The exact repository URLs and credential format must come from the organization's Artifactory documentation; do not guess them from the npm endpoint.

Also remove or override `mavenCentral()` and `gradlePluginPortal()` so a missing artifact fails closed instead of falling back to a public registry.

## 6. Use the internal container registry

Replace public base-image references such as:

```text
node:22-alpine
gradle:8.10.2-jdk17
eclipse-temurin:17-jre
nginx:1.27-alpine
postgres:13
```

with approved, mirrored image names from the internal container registry. Pin images by digest where the organization supplies stable mirrored digests.

Authenticate using the workstation's approved Docker credential helper or CI workload identity. Do not put registry passwords in Compose files.

## 7. CI configuration

Store registry credentials in the CI secret manager. A compliant CI job should:

1. authenticate to the internal container registry;
2. create or obtain a short-lived npm configuration file;
3. expose it to the Docker build as a BuildKit secret;
4. expose the corporate CA as a separate secret;
5. provide the approved Gradle init script and Artifactory credentials as secrets;
6. build with public-network egress blocked;
7. delete temporary credential files in an unconditional cleanup step.

Do not store an npm token in `SONAR_TOKEN`, reuse unrelated credentials, or write secret contents to `$GITHUB_OUTPUT` or build artifacts.

## 8. Remove the temporary OFBiz workaround

Once the asset stage works through Artifactory:

- remove the host `node_modules` bind mount from `local-dev/docker-compose.yml`;
- stop depending on a prior host-side `npm ci`;
- retain `-PskipNpmInstall` only if the dedicated asset stage supplies all required runtime assets;
- verify that `/common/js/node_modules/jquery-ui-dist/jquery-ui.min.js` is present in the final runtime;
- rebuild from a fresh checkout and empty Docker build cache.

## 9. Verification

Test without relying on local dependency caches:

```sh
docker compose -p erp-local -f local-dev/docker-compose.yml build --no-cache
docker compose -p erp-local -f local-dev/docker-compose.yml up -d
local-dev/smoke-test.sh
```

Then verify:

- build logs show only approved Artifactory and internal registry hosts;
- no request is made to `registry.npmjs.org`, Maven Central, Gradle Plugin Portal, or Docker Hub;
- legacy theme assets return HTTP 200;
- `docker history --no-trunc <image>` contains no token or `.npmrc` content;
- `git status --ignored` shows no newly generated credential file;
- the build fails if Artifactory is unavailable rather than falling back publicly.

For a stronger control, run the build in an environment where public registry DNS or egress is blocked and inspect proxy/firewall logs.

## 10. Common failure modes

- **Artifactory returns 401:** the registry URL was supplied but the authenticated `.npmrc` was not mounted, or the token has expired.
- **Certificate errors:** the corporate CA was not available inside the container or `NODE_EXTRA_CA_CERTS` points to the wrong path.
- **Build works only on one workstation:** it is using an existing host `node_modules`, Gradle cache, Docker image, or credential helper implicitly.
- **Token appears in an image layer:** `.npmrc` was copied rather than secret-mounted, or a token was passed using `ARG`/`ENV`.
- **Unexpected public traffic:** project configuration still contains `mavenCentral()`, `gradlePluginPortal()`, a public npm scope registry, or a public base-image name.

The guiding rule is simple: repository locations may be version-controlled, but credentials and machine-specific trust material must be injected at build time through approved secret mechanisms.
