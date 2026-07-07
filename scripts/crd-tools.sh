#!/usr/bin/env bash
#
# Copyright 2026 Dominik Schlosser
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs the keycloak-k8store crd-tools CLI (diff / check-cluster / apply),
# building the shaded jar first if it is missing.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

shopt -s nullglob
jars=(crd-tools/target/keycloak-k8store-crd-tools-*.jar)
shopt -u nullglob

if [ ${#jars[@]} -eq 0 ]; then
  echo "Building crd-tools shaded jar..." >&2
  mvn -q -pl crd-tools package -DskipTests
  shopt -s nullglob
  jars=(crd-tools/target/keycloak-k8store-crd-tools-*.jar)
  shopt -u nullglob
  if [ ${#jars[@]} -eq 0 ]; then
    echo "Build did not produce crd-tools/target/keycloak-k8store-crd-tools-*.jar" >&2
    exit 2
  fi
fi

exec java -jar "${jars[0]}" "$@"
