#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Regenerates the CRDs from the current Keycloak entity model and syncs them into crds/.
#
# Run this after bumping keycloak.version. Combine with scripts/crd-tools.sh to see whether the
# new Keycloak version changed the schemas in a compatible or breaking way.
# CI keeps this honest: it runs the build and fails on `git diff --exit-code crds/`.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q -pl core -DskipTests package

mkdir -p crds
for f in core/target/classes/META-INF/fabric8/*.k8store.dominikschlosser.github.io-v1.yml; do
  cp "$f" "crds/$(basename "$f")"
done

echo "CRDs in crds/:"
ls -1 crds/
if ! git diff --quiet -- crds/ 2>/dev/null; then
  echo
  echo "crds/ changed - review with: git diff crds/  (and run scripts/crd-tools.sh for a semantic diff)"
fi
