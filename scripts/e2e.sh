#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Runs the tests module against the Keycloak deployed in the kind cluster
# (KC_TEST_SERVER=remote), with a background port-forward on 8080 + 9000.
# Extra arguments are passed to mvn, e.g.: scripts/e2e.sh -Dtest=WriteModeStorageTest
set -euo pipefail
cd "$(dirname "$0")/.."

KUBECTL="kubectl --context kind-k8store"

${KUBECTL} -n keycloak port-forward svc/keycloak 8080:8080 9000:9000 >/dev/null 2>&1 &
PF_PID=$!
cleanup() { kill "${PF_PID}" 2>/dev/null || true; }
trap cleanup EXIT

echo "Waiting for Keycloak readiness through the port-forward..."
READY=false
for _ in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9000/health/ready || true)" = "200" ]; then
    READY=true
    break
  fi
  sleep 2
done
if [ "${READY}" != true ]; then
  echo "Keycloak did not become ready via http://localhost:9000/health/ready" >&2
  exit 1
fi
echo "Keycloak is ready. Running tests..."

LOG=$(mktemp)
set +e
KC_TEST_SERVER=remote mvn -pl tests test "$@" | tee "${LOG}"
RC=${PIPESTATUS[0]}
set -e

if grep -qE 'No tests to run|Tests run: 0[,.]' "${LOG}"; then
  echo
  echo "NOTE: the tests module contains no tests (yet) - nothing was executed against the cluster."
fi
rm -f "${LOG}"
exit "${RC}"
