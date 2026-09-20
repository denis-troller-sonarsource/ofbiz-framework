#!/usr/bin/env bash
# Resolve a SonarCloud project's UUID from its human-readable key.
#
# Validates $SONARQUBE_TOKEN first (an invalid token produces the same ambiguous
# 403 as a permissions problem), then queries the v2 /projects/projects endpoint.
# Prints the project UUID to stdout on success; all diagnostics go to stderr so
# the output is safe to capture:  PROJECT_ID=$(resolve-project-id.sh "$KEY")
#
# Usage:   resolve-project-id.sh <project-key>
# Env:     SONARQUBE_TOKEN  (required)  SonarCloud user token
# Requires: curl, jq
set -euo pipefail

die() { echo "resolve-project-id: $*" >&2; exit 1; }

KEY="${1:-}"
[[ -n "$KEY" ]] || die "usage: resolve-project-id.sh <project-key>"
[[ -n "${SONARQUBE_TOKEN:-}" ]] || die "SONARQUBE_TOKEN is not set"
command -v curl >/dev/null 2>&1 || die "curl is required"
command -v jq   >/dev/null 2>&1 || die "jq is required"

# Step 1 — validate the token. Bad tokens and under-privileged tokens both yield
# an opaque 403 on the v2 endpoint, so check the clear boolean here first.
valid=$(curl -sS "https://sonarcloud.io/api/authentication/validate" \
  -H "authorization: Bearer ${SONARQUBE_TOKEN}" | jq -r '.valid')
[[ "$valid" == "true" ]] || die "SONARQUBE_TOKEN is invalid/expired — regenerate and re-export it"

# Step 2 — resolve the UUID. Note the lowercase 'authorization:' header and the
# /projects/projects path; both are required for api.sonarcloud.io (AWS API GW).
id=$(curl -sS -X GET \
  "https://api.sonarcloud.io/projects/projects?keys=${KEY}&pageIndex=1&pageSize=50" \
  -H 'accept: application/json' \
  -H "authorization: Bearer ${SONARQUBE_TOKEN}" \
  | jq -r '.projects[0].id // empty')

[[ -n "$id" ]] || die "no project found for key '${KEY}' (check the exact, case-sensitive key)"
echo "$id"
