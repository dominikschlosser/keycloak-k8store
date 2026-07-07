#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Tears down the local test infrastructure created by scripts/kind-up.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER_NAME=k8store
REG_NAME=k8store-registry

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  kind delete cluster --name "${CLUSTER_NAME}"
else
  echo "kind cluster ${CLUSTER_NAME} does not exist"
fi

if docker inspect "${REG_NAME}" >/dev/null 2>&1; then
  docker rm -f "${REG_NAME}" >/dev/null
  echo "Removed registry container ${REG_NAME}"
else
  echo "Registry container ${REG_NAME} does not exist"
fi
