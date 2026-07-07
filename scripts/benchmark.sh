#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Load-test comparison of the k8store datastore against vanilla Keycloak, using Keycloak's
# official load-testing tool keycloak-benchmark (kcb, Gatling-based,
# https://www.keycloak.org/keycloak-benchmark/) against the local 2-node kind cluster
# (scripts/kind-up.sh + scripts/deploy.sh).
#
# Variants (run sequentially, same manifests, same postgres, fresh namespace + DB each):
#   k8store            deploy/Dockerfile image: config entities served from CR informer
#                      mirrors, users/sessions in postgres (read-only=false)
#   vanilla-stateless  stock nightly, --features=stateless --db=postgres: the same feature
#                      set with the JPA config store — isolates the storage-layer difference
#   vanilla-default    stock nightly, default features: embedded Infinispan caches with
#                      jdbc-ping clustering, as Keycloak is commonly run today
# The vanilla variants reuse deploy/30-keycloak.yaml and are patched at runtime (image/args/
# env). Keycloak's non-optimized `start` re-augments inside the pod, so the runtime args are
# authoritative — no second Dockerfile needed. All variants share the exact same base image:
# the locally cached quay.io/keycloak/keycloak:nightly (also the k8store image base) is pushed
# to the local registry as keycloak-vanilla:dev, so quay's rolling :nightly tag cannot drift
# between variants.
#
# Access path: the load generator runs INSIDE the cluster as pod kcb (eclipse-temurin:21-jdk,
# pinned to the control-plane node so it does not share a worker with a Keycloak replica) and
# targets the ClusterIP service http://keycloak.keycloak:8080. Rationale: on macOS/Docker
# Desktop the kind node IPs are not routable from the host (verified: 172.21.0.0/16 does not
# answer), and `kubectl port-forward` funnels all load through a single connection, which
# distorts results. Seeding and sanity checks do use a short-lived port-forward — they are
# low-rate and not part of the measured path.
#
# Per variant: clean namespace (fresh DB), deploy, wait ready, seed (realm `benchmark`,
# confidential client client-0/client-0-secret with service accounts, users user-0..N-1 with
# password user-<i>-password — kcb's built-in naming conventions), sanity-gate (token endpoint
# 200 through the in-cluster path), then per scenario one discarded warm-up pass followed by
# the measured run (--filter-results=true limits Gatling stats to the measurement window).
#
# Results: benchmark-results/<variant>-<scenario>/ (gitignored) with the full Gatling report,
# gatling logs, a mid-run `docker stats` sample of the kind nodes (no metrics-server in the
# cluster, so no `kubectl top`), and summary.json. A comparison table is printed and written
# to benchmark-results/summary.txt. The kcb release zip is cached in .benchmark-cache/.
#
# Usage: scripts/benchmark.sh
# Tunables (env):
#   CS_USERS_PER_SEC (20)  arrival rate for ClientSecret (1 token request per user)
#   AC_USERS_PER_SEC (8)   arrival rate for AuthorizationCode (~4 requests per user)
#   RAMP_UP (30) MEASUREMENT (120) WARMUP_MEASUREMENT (30) USERS (100)
#   VARIANTS ("k8store vanilla-stateless vanilla-default")
#   SCENARIOS ("client-secret authorization-code")
#   RESTORE (true)         redeploy the k8store variant and remove the kcb pod at the end
set -euo pipefail
cd "$(dirname "$0")/.."

KCB_VERSION=999.0.0-SNAPSHOT
KCB_DIST_URL="https://github.com/keycloak/keycloak-benchmark/releases/download/${KCB_VERSION}/keycloak-benchmark-${KCB_VERSION}.tar.gz"
KCB_DIR=".benchmark-cache/keycloak-benchmark-${KCB_VERSION}"
KUBECTL="kubectl --context kind-k8store"
VANILLA_IMAGE=localhost:5001/keycloak-vanilla:dev
NIGHTLY_IMAGE=quay.io/keycloak/keycloak:nightly
SERVER_URL=http://keycloak.keycloak:8080   # in-cluster, from the kcb pod
PF_URL=http://localhost:18080              # host-side, seeding/sanity only
RESULTS=benchmark-results

CS_USERS_PER_SEC="${CS_USERS_PER_SEC:-20}"
AC_USERS_PER_SEC="${AC_USERS_PER_SEC:-8}"
RAMP_UP="${RAMP_UP:-30}"
MEASUREMENT="${MEASUREMENT:-120}"
WARMUP_MEASUREMENT="${WARMUP_MEASUREMENT:-30}"
USERS="${USERS:-100}"
VARIANTS="${VARIANTS:-k8store vanilla-stateless vanilla-default}"
SCENARIOS="${SCENARIOS:-client-secret authorization-code}"
RESTORE="${RESTORE:-true}"

PF_PID=""
cleanup() { [ -n "${PF_PID}" ] && kill "${PF_PID}" 2>/dev/null || true; }
trap cleanup EXIT

# ---------------------------------------------------------------------------- kcb dist + pod

ensure_kcb_dist() {
  if [ ! -f "${KCB_DIR}/bin/kcb.sh" ]; then
    echo "Downloading keycloak-benchmark ${KCB_VERSION} ..."
    mkdir -p .benchmark-cache
    curl -sfL -o ".benchmark-cache/keycloak-benchmark-${KCB_VERSION}.tar.gz" "${KCB_DIST_URL}"
    tar xzf ".benchmark-cache/keycloak-benchmark-${KCB_VERSION}.tar.gz" -C .benchmark-cache
  fi
  echo "kcb dist: ${KCB_DIR}"
}

ensure_kcb_pod() {
  cat <<EOF | ${KUBECTL} apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: kcb
  namespace: default
  labels:
    app: kcb
spec:
  nodeSelector:
    node-role.kubernetes.io/control-plane: ""
  tolerations:
    - key: node-role.kubernetes.io/control-plane
      operator: Exists
      effect: NoSchedule
  containers:
    - name: kcb
      image: eclipse-temurin:21-jdk
      command: ["sleep", "infinity"]
EOF
  ${KUBECTL} wait --for=condition=Ready pod/kcb --timeout=300s
  if ! ${KUBECTL} exec kcb -- test -f /opt/kcb/bin/kcb.sh 2>/dev/null; then
    echo "Copying kcb dist into the kcb pod ..."
    ${KUBECTL} cp "${KCB_DIR}" default/kcb:/opt/kcb
    ${KUBECTL} exec kcb -- test -f /opt/kcb/bin/kcb.sh
  fi
  # curl: used for the in-cluster sanity gate; jq/uuidgen: used by kcb.sh's result aggregation
  ${KUBECTL} exec kcb -- bash -c \
    'command -v curl >/dev/null && command -v jq >/dev/null || { apt-get update -qq && apt-get install -y -qq curl jq uuid-runtime >/dev/null; }'
}

# ---------------------------------------------------------------------------- images

ensure_vanilla_image() {
  # Reuse the locally cached nightly (the same image the k8store build is based on) instead of
  # letting the nodes pull quay's rolling tag — keeps all variants on one image digest.
  if ! docker image inspect "${NIGHTLY_IMAGE}" >/dev/null 2>&1; then
    docker pull "${NIGHTLY_IMAGE}"
  fi
  docker tag "${NIGHTLY_IMAGE}" "${VANILLA_IMAGE}"
  docker push "${VANILLA_IMAGE}" >/dev/null
  echo "Vanilla image pushed: ${VANILLA_IMAGE}"
}

# ---------------------------------------------------------------------------- deploy variants

deploy_variant() {
  local variant=$1
  echo
  echo "=== [${variant}] clean namespace + deploy"
  ${KUBECTL} delete namespace keycloak --ignore-not-found --timeout=300s
  if [ "${variant}" = "k8store" ]; then
    # scripts/deploy.sh builds/pushes the image if needed, applies CRDs + manifests, waits and
    # smoke-checks (master realm, admin login, bootstrap client grant).
    ./scripts/deploy.sh
    return
  fi

  local extra_args=""
  [ "${variant}" = "vanilla-stateless" ] && extra_args='"--features=stateless",'
  ${KUBECTL} apply -f deploy/
  ${KUBECTL} -n keycloak patch deployment keycloak --type=json -p '[
    {"op":"replace","path":"/spec/template/spec/containers/0/image","value":"'"${VANILLA_IMAGE}"'"},
    {"op":"replace","path":"/spec/template/spec/containers/0/args","value":["start","--db=postgres",'"${extra_args}"'"--health-enabled=true","--metrics-enabled=true","--hostname-strict=false","--http-enabled=true"]}
  ]'
  # Drop the k8store SPI toggle; there is no k8store provider in the stock image.
  ${KUBECTL} -n keycloak set env deployment/keycloak KC_SPI_DATASTORE__K8STORE__READ_ONLY-
  ${KUBECTL} -n keycloak rollout status deployment/postgres --timeout=300s
  ${KUBECTL} -n keycloak rollout status deployment/keycloak --timeout=600s
}

record_cluster_view() {
  # vanilla-default only: verify the two replicas actually formed an Infinispan cluster
  # (nightly's default JGroups stack is jdbc-ping, discovery via the shared postgres).
  local out=$1 view=""
  for pod in $(${KUBECTL} -n keycloak get pods -l app=keycloak -o name); do
    view=$(${KUBECTL} -n keycloak logs "${pod}" 2>/dev/null | grep ISPN000094 | tail -1 || true)
    echo "${pod}: ${view:-<no cluster view logged>}" >> "${out}"
  done
  if grep -q '(2)' "${out}"; then
    echo "Infinispan cluster view OK (2 members)"
  else
    echo "WARNING: no 2-member Infinispan cluster view found; see ${out}" >&2
  fi
}

# ---------------------------------------------------------------------------- seeding (port-forward)

start_pf() {
  ${KUBECTL} -n keycloak port-forward svc/keycloak 18080:8080 >/dev/null 2>&1 &
  PF_PID=$!
  local status=000
  for _ in $(seq 1 60); do
    status=$(curl -s -o /dev/null -w '%{http_code}' "${PF_URL}/realms/master" || true)
    [ "${status}" = "200" ] && return 0
    sleep 2
  done
  echo "ERROR: /realms/master did not answer 200 through the port-forward (last: ${status})" >&2
  return 1
}

stop_pf() {
  [ -n "${PF_PID}" ] && kill "${PF_PID}" 2>/dev/null || true
  PF_PID=""
}

mint_admin_token() {
  curl -sf -d client_id=admin-cli -d username=admin -d password=admin -d grant_type=password \
    "${PF_URL}/realms/master/protocol/openid-connect/token" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])'
}

post_json() { # <token> <url> <json>  -> prints http status
  curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "$3" "$2"
}

seed() {
  echo "Seeding realm 'benchmark': client-0 + ${USERS} users ..."
  local token code i
  token=$(mint_admin_token)
  code=$(post_json "${token}" "${PF_URL}/admin/realms" '{"realm":"benchmark","enabled":true}')
  [ "${code}" = "201" ] || { echo "ERROR: realm creation returned HTTP ${code}" >&2; return 1; }
  # Confidential client used by both scenarios; naming matches kcb's defaults
  # (initialize-benchmark-entities.sh in the kcb release creates the same client).
  code=$(post_json "${token}" "${PF_URL}/admin/realms/benchmark/clients" '{
    "clientId": "client-0",
    "enabled": true,
    "clientAuthenticatorType": "client-secret",
    "secret": "client-0-secret",
    "redirectUris": ["*"],
    "serviceAccountsEnabled": true,
    "publicClient": false,
    "protocol": "openid-connect",
    "attributes": {"post.logout.redirect.uris": "+"}
  }')
  [ "${code}" = "201" ] || { echo "ERROR: client creation returned HTTP ${code}" >&2; return 1; }
  for i in $(seq 0 $((USERS - 1))); do
    # the bootstrap admin token only lives 60s — re-mint while iterating
    [ $((i % 25)) -eq 0 ] && token=$(mint_admin_token)
    code=$(post_json "${token}" "${PF_URL}/admin/realms/benchmark/users" '{
      "username": "user-'"${i}"'",
      "enabled": true,
      "firstName": "User",
      "lastName": "Benchmark",
      "email": "user-'"${i}"'@benchmark.local",
      "credentials": [{"type": "password", "value": "user-'"${i}"'-password", "temporary": false}]
    }')
    [ "${code}" = "201" ] || { echo "ERROR: user user-${i} creation returned HTTP ${code}" >&2; return 1; }
  done
  echo "Seeded ${USERS} users."
}

sanity_gate() {
  # Token endpoint must answer 200 through the measured (in-cluster) access path.
  local code
  code=$(${KUBECTL} exec kcb -- curl -s -o /dev/null -w '%{http_code}' \
    -d client_id=client-0 -d client_secret=client-0-secret -d grant_type=client_credentials \
    "${SERVER_URL}/realms/benchmark/protocol/openid-connect/token")
  if [ "${code}" != "200" ]; then
    echo "ERROR: in-cluster token endpoint sanity check returned HTTP ${code}" >&2
    return 1
  fi
  echo "Sanity gate OK: in-cluster client_credentials token request returned 200"
}

# ---------------------------------------------------------------------------- benchmark runs

run_scenario() {
  local variant=$1 scenario=$2 class rate
  case "${scenario}" in
    client-secret)      class=keycloak.scenario.authentication.ClientSecret; rate="${CS_USERS_PER_SEC}" ;;
    authorization-code) class=keycloak.scenario.authentication.AuthorizationCode; rate="${AC_USERS_PER_SEC}" ;;
    *) echo "Unknown scenario: ${scenario}" >&2; return 1 ;;
  esac
  local out="${RESULTS}/${variant}-${scenario}"
  rm -rf "${out}"
  mkdir -p "${out}"
  local common=(
    "--scenario=${class}"
    "--server-url=${SERVER_URL}"
    "--realm-name=benchmark"
    "--users-per-realm=${USERS}"
    "--client-id=client-0"
    "--client-secret=client-0-secret"
    "--users-per-sec=${rate}"
    # NOT using kcb's --filter-results: its LogProcessor cannot parse the simulation.log format
    # of the Gatling version bundled in this release ("Unknow log entry type"), which crashes the
    # run before report generation. Stats therefore include the ramp-up phase — identical for
    # every variant, so the comparison is unaffected.
    "--sla-error-percentage=100"
    "--sla-mean-response-time=60000"
  )

  echo "--- [${variant}/${scenario}] warm-up: ${WARMUP_MEASUREMENT}s at ${rate} users/s (discarded)"
  ${KUBECTL} exec kcb -- bash /opt/kcb/bin/kcb.sh "${common[@]}" \
    --ramp-up=5 "--measurement=${WARMUP_MEASUREMENT}" > "${out}/warmup.log" 2>&1 || true
  ${KUBECTL} exec kcb -- rm -rf /opt/kcb/results

  echo "--- [${variant}/${scenario}] measuring: ${RAMP_UP}s ramp + ${MEASUREMENT}s at ${rate} users/s"
  ( sleep $((RAMP_UP + MEASUREMENT / 2)); docker stats --no-stream > "${out}/docker-stats-mid.txt" 2>/dev/null || true ) &
  local stats_pid=$!
  ${KUBECTL} exec kcb -- bash /opt/kcb/bin/kcb.sh "${common[@]}" \
    "--ramp-up=${RAMP_UP}" "--measurement=${MEASUREMENT}" > "${out}/gatling.log" 2>&1 || true
  wait "${stats_pid}" 2>/dev/null || true
  ${KUBECTL} cp default/kcb:/opt/kcb/results "${out}/gatling" >/dev/null

  python3 - "${out}" "${variant}" "${scenario}" "${rate}" <<'PYEOF'
import glob, json, os, sys

out, variant, scenario, rate = sys.argv[1:5]
paths = glob.glob(os.path.join(out, "gatling", "*", "js", "stats.json"))
if not paths:
    sys.exit(f"ERROR: no Gatling stats.json under {out}/gatling - check {out}/gatling.log")
report = json.load(open(paths[0]))
g = report["stats"]
summary = {
    "variant": variant,
    "scenario": scenario,
    "usersPerSec": float(rate),
    "requests": g["numberOfRequests"]["total"],
    "errors": g["numberOfRequests"]["ko"],
    "reqPerSec": g["meanNumberOfRequestsPerSecond"]["total"],
    "meanMs": g["meanResponseTime"]["ok"],
    "p50Ms": g["percentiles1"]["ok"],
    "p75Ms": g["percentiles2"]["ok"],
    "p95Ms": g["percentiles3"]["ok"],
    "p99Ms": g["percentiles4"]["ok"],
    "maxMs": g["maxResponseTime"]["ok"],
    "perRequest": {
        v["stats"]["name"]: {
            "count": v["stats"]["numberOfRequests"]["total"],
            "ko": v["stats"]["numberOfRequests"]["ko"],
            "meanMs": v["stats"]["meanResponseTime"]["ok"],
            "p50Ms": v["stats"]["percentiles1"]["ok"],
            "p95Ms": v["stats"]["percentiles3"]["ok"],
            "p99Ms": v["stats"]["percentiles4"]["ok"],
        }
        for v in report.get("contents", {}).values()
    },
}
with open(os.path.join(out, "summary.json"), "w") as f:
    json.dump(summary, f, indent=2)
print(f"    req/s={summary['reqPerSec']:.1f} mean={summary['meanMs']}ms "
      f"p50={summary['p50Ms']} p95={summary['p95Ms']} p99={summary['p99Ms']} "
      f"max={summary['maxMs']} errors={summary['errors']}")
if summary["errors"]:
    print(f"    WARNING: {summary['errors']} failed requests", file=sys.stderr)
PYEOF
}

summarize() {
  python3 - "${RESULTS}" <<'PYEOF'
import glob, json, os, sys

results = sys.argv[1]
rows = []
for path in sorted(glob.glob(os.path.join(results, "*", "summary.json"))):
    s = json.load(open(path))
    rows.append((s["scenario"], s["variant"], s["usersPerSec"], s["requests"], s["reqPerSec"],
                 s["meanMs"], s["p50Ms"], s["p95Ms"], s["p99Ms"], s["maxMs"], s["errors"]))
rows.sort(key=lambda r: (r[0], r[1]))
header = ("scenario", "variant", "users/s", "requests", "req/s", "mean", "p50", "p95", "p99", "max", "errors")
widths = [max(len(str(x)) for x in [h] + [f"{r[i]:.1f}" if isinstance(r[i], float) else str(r[i]) for r in rows])
          for i, h in enumerate(header)]
def fmt(row):
    return "  ".join((f"{v:.1f}" if isinstance(v, float) else str(v)).rjust(w) if i >= 2 else str(v).ljust(w)
                     for i, (v, w) in enumerate(zip(row, widths)))
lines = [fmt(header)] + [fmt(r) for r in rows]
table = "\n".join(lines)
print("\n" + table)
with open(os.path.join(results, "summary.txt"), "w") as f:
    f.write(table + "\n")
PYEOF
}

# ---------------------------------------------------------------------------- main

ensure_kcb_dist
ensure_vanilla_image
ensure_kcb_pod
mkdir -p "${RESULTS}"

for variant in ${VARIANTS}; do
  deploy_variant "${variant}"
  if [ "${variant}" = "vanilla-default" ]; then
    mkdir -p "${RESULTS}"
    record_cluster_view "${RESULTS}/vanilla-default-cluster-view.txt"
  fi
  start_pf
  seed
  stop_pf
  sanity_gate
  for scenario in ${SCENARIOS}; do
    run_scenario "${variant}" "${scenario}"
  done
done

if [ "${RESTORE}" = "true" ]; then
  echo
  echo "=== Restoring the k8store deployment"
  ${KUBECTL} delete pod kcb --ignore-not-found >/dev/null
  ${KUBECTL} delete namespace keycloak --ignore-not-found --timeout=300s
  ./scripts/deploy.sh
fi

summarize
echo
echo "Full Gatling reports: ${RESULTS}/<variant>-<scenario>/gatling/*/index.html"
