#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Creates (idempotently) the local test infrastructure:
#   - a docker registry container `k8store-registry` on localhost:5001
#   - a kind cluster `k8store` with 1 control-plane and 2 worker nodes,
#     wired to the local registry (standard kind local-registry pattern)
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER_NAME=k8store
REG_NAME=k8store-registry
REG_PORT=5001

# 1. Local registry container
if [ "$(docker inspect -f '{{.State.Running}}' "${REG_NAME}" 2>/dev/null || true)" != 'true' ]; then
  docker rm -f "${REG_NAME}" 2>/dev/null || true
  docker run -d --restart=always -p "127.0.0.1:${REG_PORT}:5000" \
    --network bridge --name "${REG_NAME}" registry:2
  echo "Started local registry ${REG_NAME} on localhost:${REG_PORT}"
else
  echo "Local registry ${REG_NAME} already running"
fi

# Optionally publish the keycloak Service's NodePorts (deploy/30-keycloak.yaml) on the host so
# http://localhost:8080 reaches the DEPLOYED Keycloak with no port-forward. OPT-IN
# (KIND_PUBLISH_KEYCLOAK_PORTS=1) because binding host 8080/9000 collides with the embedded
# integration test server, which is hardwired to localhost:8080/9000 - publishing them by
# default breaks `mvn install` and the CI integration job (Port already bound: 8080). e2e.sh
# port-forwards these ports itself, so the e2e flow does not need the mapping either.
CONTROL_PLANE="- role: control-plane"
if [ "${KIND_PUBLISH_KEYCLOAK_PORTS:-}" = "1" ]; then
  # NodePorts answer on every node, so binding them on the control-plane still routes to the
  # worker pods.
  CONTROL_PLANE="${CONTROL_PLANE}
  extraPortMappings:
  - containerPort: 30080
    hostPort: 8080
    protocol: TCP
  - containerPort: 30900
    hostPort: 9000
    protocol: TCP"
fi

# 2. kind cluster: 1 control-plane + 2 workers (the 2 Keycloak replicas are
#    forced onto distinct worker nodes via required podAntiAffinity)
if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  echo "kind cluster ${CLUSTER_NAME} already exists"
else
  cat <<EOF | kind create cluster --name "${CLUSTER_NAME}" --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
${CONTROL_PLANE}
- role: worker
- role: worker
containerdConfigPatches:
- |-
  [plugins."io.containerd.grpc.v1.cri".registry]
    config_path = "/etc/containerd/certs.d"
EOF
fi

# 3. Tell containerd on every node how to reach the registry as localhost:5001
REGISTRY_DIR="/etc/containerd/certs.d/localhost:${REG_PORT}"
for node in $(kind get nodes --name "${CLUSTER_NAME}"); do
  docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
  cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${REG_NAME}:5000"]
EOF
done

# 4. Connect the registry to the kind network so nodes can reach it
if [ "$(docker inspect -f='{{json .NetworkSettings.Networks.kind}}' "${REG_NAME}")" = 'null' ]; then
  docker network connect "kind" "${REG_NAME}"
fi

# 5. Document the local registry for tooling
cat <<EOF | kubectl --context "kind-${CLUSTER_NAME}" apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${REG_PORT}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF

kubectl --context "kind-${CLUSTER_NAME}" get nodes
echo
echo "Cluster ${CLUSTER_NAME} is up. Next: scripts/deploy.sh"
