#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Builds and deploys Keycloak 26.7.0 + the k8store extension into the kind cluster
# created by scripts/kind-up.sh.
#
# Usage: scripts/deploy.sh [--build] [--read-only true|false]
#   --build              force rebuild of the provider jar (mvn -pl core package)
#   --read-only VALUE    set the k8store read-only mode (default: false).
#                        Note: the very first deploy must run with read-only=false,
#                        otherwise Keycloak cannot bootstrap the master realm.
set -euo pipefail
cd "$(dirname "$0")/.."

KUBECTL="kubectl --context kind-k8store"
IMAGE=localhost:5001/keycloak-k8store:dev
# The core build stages the thin provider jar + its runtime dependency jars here.
JAR=core/target/providers/keycloak-k8store-0.1.0-SNAPSHOT.jar

BUILD=false
READ_ONLY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --build) BUILD=true; shift ;;
    --read-only) READ_ONLY="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done
case "${READ_ONLY}" in true|false) ;; *) echo "--read-only must be true or false" >&2; exit 1 ;; esac

# 1. Provider jars (thin jar + runtime dependency jars staged in core/target/providers/)
if [ "${BUILD}" = true ] || [ ! -f "${JAR}" ]; then
  echo "Building provider jars..."
  mvn -q -pl core -DskipTests package
fi

# 2. Image
echo "Building and pushing ${IMAGE}..."
docker build -f deploy/Dockerfile -t "${IMAGE}" .
docker push "${IMAGE}"

# 3. CRDs
${KUBECTL} apply --server-side -f crds/

# 4. Manifests (files are numbered so directory order is the apply order)
${KUBECTL} apply -f deploy/

# 5. Read-only toggle (changing the env value triggers a rollout)
${KUBECTL} -n keycloak set env deployment/keycloak "KC_SPI_DATASTORE__K8STORE__READ_ONLY=${READ_ONLY}"

# 6. Roll out a restart so a rebuilt :dev image is actually picked up
${KUBECTL} -n keycloak rollout restart deployment/keycloak

${KUBECTL} -n keycloak rollout status deployment/postgres --timeout=300s
${KUBECTL} -n keycloak rollout status deployment/keycloak --timeout=600s

# 7. Smoke check through a short-lived port-forward: master realm must answer 200, the
#    bootstrap admin user (admin/admin) and the bootstrap service client
#    (temp-admin/mysecret, used by the Keycloak test framework's remote mode) must work.
echo "Smoke check: GET /realms/master ..."
${KUBECTL} -n keycloak port-forward svc/keycloak 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "${PF_PID}" 2>/dev/null || true' EXIT
STATUS=000
for _ in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/realms/master || true)
  [ "${STATUS}" = "200" ] && break
  sleep 2
done
if [ "${STATUS}" != "200" ]; then
  echo "Smoke check FAILED: /realms/master returned HTTP ${STATUS}" >&2
  exit 1
fi
echo "Smoke check OK: /realms/master returned HTTP 200"

ERR=$(curl -s -d 'client_id=admin-cli' -d 'username=admin' -d 'password=admin' \
  -d 'grant_type=password' http://localhost:18080/realms/master/protocol/openid-connect/token \
  | (grep -o '"error_description":"[^"]*"' || true))
CLIENT_GRANT=$(curl -s -o /dev/null -w '%{http_code}' -d 'client_id=temp-admin' -d 'client_secret=mysecret' \
  -d 'grant_type=client_credentials' http://localhost:18080/realms/master/protocol/openid-connect/token || true)
kill "${PF_PID}" 2>/dev/null || true
trap - EXIT
if [ -n "${ERR}" ]; then
  echo "Smoke check FAILED: admin login on master realm: ${ERR}" >&2
  exit 1
fi
if [ "${CLIENT_GRANT}" != "200" ]; then
  echo "Smoke check FAILED: temp-admin client_credentials grant returned HTTP ${CLIENT_GRANT}" >&2
  exit 1
fi
echo "Smoke check OK: admin/admin password grant and temp-admin client grant work"

${KUBECTL} -n keycloak get pods -o wide
cat <<'EOF'

Deployment done. Useful commands:
  Reach Keycloak on the host. If the cluster was created with
  KIND_PUBLISH_KEYCLOAK_PORTS=1 scripts/kind-up.sh, the NodePorts are already on the host:
    http://localhost:8080  (admin console; bootstrap admin/admin)
    http://localhost:9000  (management: health/metrics)
  Otherwise (the default, which keeps 8080/9000 free for the integration tests), port-forward:
    kubectl -n keycloak port-forward svc/keycloak 8080:8080 9000:9000
  Inspect the CRs backing the config store:
    kubectl -n keycloak get keycloakrealms
    kubectl -n keycloak get keycloakclients
EOF
