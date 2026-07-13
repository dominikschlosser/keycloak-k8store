#!/usr/bin/env bash
#
# Delete every k8store CR belonging to a realm: the realm itself plus all of its
# clients, scopes, roles, groups, users, sessions, etc.
#
# Deleting the KeycloakRealm CR alone does NOT cascade — each entity is stored as
# its own CR. They are all stamped with the realm label
#   k8store.dominikschlosser.github.io/realm=<realm>
# so we enumerate every CRD in the k8store API group and delete by that selector.
#
# Usage:
#   scripts/delete-realm.sh <realm> [-n namespace] [--yes] [--dry-run]
#
# Environment:
#   K8STORE_NAMESPACE   default namespace (overridden by -n); falls back to "keycloak"
#
set -euo pipefail

GROUP="k8store.dominikschlosser.github.io"
REALM_LABEL="${GROUP}/realm"

namespace="${K8STORE_NAMESPACE:-keycloak}"
realm=""
assume_yes=0
dry_run=0

usage() {
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -n|--namespace) namespace="$2"; shift 2 ;;
        -y|--yes)       assume_yes=1; shift ;;
        --dry-run)      dry_run=1; shift ;;
        -h|--help)      usage 0 ;;
        -*)             echo "Unknown flag: $1" >&2; usage 1 ;;
        *)
            if [ -n "$realm" ]; then echo "Unexpected argument: $1" >&2; usage 1; fi
            realm="$1"; shift ;;
    esac
done

[ -n "$realm" ] || { echo "Error: realm name is required" >&2; usage 1; }

# Mirror the extension's sanitizeLabel() for the common case: disallowed characters
# become '-', edges trimmed to alphanumeric. Names >63 chars get hashed server-side
# and cannot be reproduced here — bail rather than delete the wrong thing.
label_value="$(printf '%s' "$realm" \
    | sed -e 's/[^A-Za-z0-9._-]/-/g' -e 's/^[^A-Za-z0-9]*//' -e 's/[^A-Za-z0-9]*$//')"
[ -n "$label_value" ] || label_value="x"
if [ "${#label_value}" -gt 63 ]; then
    echo "Error: realm '$realm' sanitizes to a >63-char label that is hashed server-side;" >&2
    echo "       delete it by inspecting the CR labels manually." >&2
    exit 1
fi
if [ "$label_value" != "$realm" ]; then
    echo "Note: realm '$realm' maps to label value '$label_value'."
fi

selector="${REALM_LABEL}=${label_value}"

# Enumerate every namespaced CRD in the k8store API group so new kinds are covered
# automatically. Output is one resource name per line (e.g. keycloakclients).
mapfile -t kinds < <(kubectl api-resources --api-group="$GROUP" --namespaced -o name 2>/dev/null | sort)
if [ "${#kinds[@]}" -eq 0 ]; then
    echo "Error: no CRDs found for API group '$GROUP'. Are the CRDs installed and is the cluster reachable?" >&2
    exit 1
fi

echo "Realm:      $realm"
echo "Namespace:  $namespace"
echo "Selector:   $selector"
echo "Kinds:      ${#kinds[@]} ($GROUP)"
echo

# Show what will be deleted.
total=0
for kind in "${kinds[@]}"; do
    names="$(kubectl get "$kind" -n "$namespace" -l "$selector" \
        -o name 2>/dev/null || true)"
    [ -n "$names" ] || continue
    count="$(printf '%s\n' "$names" | grep -c . || true)"
    total=$((total + count))
    printf '  %-40s %s\n' "$kind" "$count"
    printf '%s\n' "$names" | sed 's/^/      /'
done

if [ "$total" -eq 0 ]; then
    echo "Nothing to delete — no CRs carry $selector in namespace $namespace."
    exit 0
fi
echo
echo "Total: $total CR(s)"

if [ "$dry_run" -eq 1 ]; then
    echo "(dry run — nothing deleted)"
    exit 0
fi

if [ "$assume_yes" -ne 1 ]; then
    printf 'Delete these %s CR(s)? [y/N] ' "$total"
    read -r reply
    case "$reply" in
        y|Y|yes|YES) ;;
        *) echo "Aborted."; exit 1 ;;
    esac
fi

for kind in "${kinds[@]}"; do
    kubectl delete "$kind" -n "$namespace" -l "$selector" --ignore-not-found 2>/dev/null || true
done

echo "Done."
