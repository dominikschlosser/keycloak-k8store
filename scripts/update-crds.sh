#!/usr/bin/env bash
# Regenerates the CRDs from the current Keycloak entity model and syncs them into crds/.
#
# Run this after bumping keycloak.version. Combine with scripts/crd-diff.sh to see whether the
# new Keycloak version changed the schemas in a compatible or breaking way.
# CI keeps this honest: it runs the build and fails on `git diff --exit-code crds/`.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q -pl core -DskipTests package

mkdir -p crds
for f in core/target/classes/META-INF/fabric8/*.keycloak.k8store.io-v1.yml; do
  cp "$f" "crds/$(basename "$f")"
done

echo "CRDs in crds/:"
ls -1 crds/
if ! git diff --quiet -- crds/ 2>/dev/null; then
  echo
  echo "crds/ changed — review with: git diff crds/  (and run scripts/crd-diff.sh for a semantic diff)"
fi
