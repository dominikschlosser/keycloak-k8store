# keycloak-k8store

Keycloak datastore extension that stores Keycloak data as **Kubernetes Custom Resources**.
By default the **configuration entities** (realms, clients, client scopes, roles, groups,
identity providers) live in CRs while dynamic data (users, sessions) stays in the database -
ideal for GitOps: your platform writes `KeycloakRealm`/`KeycloakClient` manifests, Keycloak
serves them read-only. Optionally, **every** storage area can be CR-backed.

Requires **Keycloak 26.7.0+** with the `stateless` feature. See [ARCHITECTURE.md](ARCHITECTURE.md)
for the design and the full details behind everything below.

```yaml
apiVersion: k8store.dominikschlosser.github.io/v1alpha1
kind: KeycloakClient          # kubectl get kc
metadata:
  name: my-realm.my-app
spec:
  realm: my-realm
  clientId: my-app
  enabled: true
  redirectUris: ["https://my-app.example.com/*"]
```

CRs use Keycloak's own representation JSON as their spec. Changes applied with `kubectl`/GitOps
are served by every Keycloak node within milliseconds - no restarts, no cache invalidation.

## How it fits together

```mermaid
flowchart TD
    admin["Admin console / API<br/>OIDC clients"]
    gitops["GitOps / platform<br/>kubectl, CI"]

    subgraph pod["Keycloak pod &nbsp;·&nbsp; × N identical replicas, each with its own mirror"]
        direction TB
        ds["K8sDatastoreProvider<br/>routes per area"]
        crp["CR providers<br/>realm, client, role, …"]
        be["K8sStorageBackend<br/>in-memory mirror + informers,<br/>tx write buffer, reconcile / expiry timers"]
        ds --> crp
        crp -->|"read: local mirror,<br/>no API call"| be
    end

    api["Kubernetes API server + etcd<br/>Keycloak CRs, namespaced"]
    pg[("PostgreSQL<br/>users, sessions, tokens")]

    admin -->|"HTTPS"| ds
    gitops -->|"kubectl apply CRs"| api
    api -.->|"watch, 1 per CRD kind"| be
    be -->|"periodic LIST reconcile"| api
    be ==>|"server-side apply at tx prepare,<br/>write mode only"| api
    ds -->|"JPA, non-CR areas"| pg
```

Every pod keeps its own watch-synchronized in-memory mirror of the CRs, so reads never hit the
API server and pods need no coordination - sequence diagrams and the full design are in
[ARCHITECTURE.md](ARCHITECTURE.md#how-it-works--at-a-glance).

## Quickstart (local)

```bash
scripts/kind-up.sh      # 2-worker kind cluster + local registry (needed by the tests too)
mvn install             # build + unit and integration tests
scripts/deploy.sh       # CRDs + postgres + 2 Keycloak replicas (admin/admin), write mode
scripts/deploy.sh --read-only true    # flip to the GitOps production pattern
kubectl -n keycloak get keycloakrealms,keycloakclients
scripts/kind-down.sh
```

Write mode materializes everything an admin does as CRs (useful for bootstrapping: click it
together in the console, then `kubectl get ... -o yaml` becomes your GitOps source). Read-only
mode rejects all config writes through Keycloak - the CRs are the single source of truth.

`scripts/benchmark.sh` runs a k8store-vs-vanilla load-test comparison against this cluster
using Keycloak's official keycloak-benchmark tool; results in [docs/BENCHMARK.md](docs/BENCHMARK.md).

## Deploying elsewhere

Build `core/target/providers/` (`mvn -pl core -DskipTests package`) and copy all its jars into
Keycloak's `providers/` directory (see `deploy/Dockerfile`), apply `crds/`, and give the
Keycloak service account `get,list,watch` (plus write verbs in write mode) on the
`k8store.dominikschlosser.github.io` API group (see `deploy/20-rbac.yaml`).

## Configuration

Build options (`kc.sh build`, see `deploy/Dockerfile`):

```
--features=stateless                             # required
--spi-datastore--provider=k8store
--spi-realm--jpa--enabled=false
--spi-realm-cache--default--enabled=false        # the CR mirror replaces the realm cache
--spi-authorization-cache--default--enabled=false     # when using the authorization area
--spi-organization--infinispan--enabled=false         # when using the organization area
```

Datastore options (`--spi-datastore--k8store--<option>`, or env
`KC_SPI_DATASTORE__K8STORE__<OPTION>`):

| Option | Default | Purpose |
|---|---|---|
| `read-only` | `true` | Reject config writes; CRs are managed out-of-band |
| `areas` | `config` | `config`, `all`, or a comma list (see below) |
| `namespace` | pod namespace | Namespace to watch |
| `all-namespaces` | `false` | Watch cluster-wide |
| `sync-timeout-seconds` | `120` | Max informer sync wait at boot |
| `reconcile-interval-seconds` | `60` | Upper bound on staleness if a watch connection silently stops delivering events (`0` = off) |
| `expiration-sweep-seconds` | `300` | Reaper for expired session/dynamic CRs |
| `resolve-references` | `false` | Resolve `${env:...}` / `${secret:...}` references in CR values on read (see below) |

### Areas

`areas` selects what is CR-backed; everything else falls through to Keycloak's default storage.

- **`config`** (default, the supported production pattern): `realm, client, client-scope,
  role, group, identity-provider`.
- **Opt-in config areas**: `authorization` (Authorization Services; requires `client`),
  `organization` (requires `group,identity-provider`; also disable the organization Infinispan
  cache - and the `organization` *feature* must stay disabled unless this area is on).
- **Dynamic areas** (always writable, even in read-only mode): `user-session, auth-session,
  login-failure, single-use-object, revoked-token, user`.
- **`all`** = everything above.

**The dynamic areas are experimental**: every login becomes CR writes (etcd churn and size
limits apply), and CR writes are transaction-buffered but not atomic with the database. User
CRs contain **credential hashes and broker tokens - lock down RBAC on `keycloakusers`**.

### Secret and environment references

Some CR values are secrets - a client `secret`, the realm `smtpServer` password, an identity
provider `clientSecret`, an LDAP `bindCredential`. With `resolve-references=true` these can live in
a Kubernetes `Secret` (or an environment variable) and be referenced from the CR, so the manifest
you commit to git only holds a reference. References are resolved on read; the CR itself is served
verbatim, so the resolved value never lands back in the stored CR.

References are placeholders inside any string value, recognized only with an explicit prefix (so
ordinary values and Keycloak's own `${...}` tokens are untouched):

- `${env:NAME}` - the pod environment variable `NAME`
- `${env:NAME:-default}` - `NAME`, or `default` when it is unset or empty
- `${secret:secret-name:key}` - key `key` of the `Secret` `secret-name` in the watched namespace
- `$$` - a literal `$`

```yaml
apiVersion: k8store.dominikschlosser.github.io/v1alpha1
kind: KeycloakClient
metadata:
  name: master.my-app
spec:
  realm: master
  clientId: my-app
  secret: ${secret:kc-client-secrets:my-app}   # from Secret kc-client-secrets, key my-app
```

Referenced Secrets must live in the datastore's own namespace (a reference carries no namespace),
and the service account needs `get,list,watch` on `secrets` (already in `deploy/20-rbac.yaml`,
commented as such). A reference that cannot be resolved (missing Secret/key, unset variable with no
default) is left in place verbatim and logged - it fails open, visibly.

References are resolved only in the config kinds (realm, client, ...); the always-writable runtime
kinds (users, sessions, ...) are Keycloak-owned data and are served verbatim.

`resolve-references` **requires `read-only` mode** (the default) and the boot fails otherwise.
Resolution happens on read, and the admin console reads through the same path, so it sees the
resolved value; in write mode a save would map the whole representation back and persist that
resolved value into the CR in clear, overwriting the reference. Read-only mode forbids config
writes, so references stay intact.

## CRD kinds

20 kinds under `k8store.dominikschlosser.github.io/v1alpha1` (manifests in `crds/`, regenerated by
`scripts/update-crds.sh`). Config: `KeycloakRealm` (kr), `KeycloakClient` (kc),
`KeycloakClientScope` (kcs), `KeycloakRole` (kro), `KeycloakGroup` (kg); authorization:
krs/kazr/kazs/kazp/kpt; organizations: korg/korginv; dynamic: ku/kus/kas/klf/ksuo/krt/kuvc/kivc.
Identity providers are embedded in the realm spec. On Keycloak version bumps the CRDs regenerate
and apply without downtime - see below.

## Keycloak version upgrades

Two things happen on a Keycloak version bump, and only one of them is automatic:

- **Schema**: `scripts/update-crds.sh` regenerates the CRD schemas from the new Keycloak;
  `scripts/crd-tools.sh` classifies the changes and applies them without downtime. Database
  (Liquibase) migrations for DB-stored data run normally.
- **Content**: Keycloak's built-in *model migrations* - boot-time rewrites of stored config,
  e.g. "add the new built-in `basic` client scope to every realm" (Keycloak 25) - are
  **deliberately skipped for CR data**. In a GitOps store, a hidden boot-time write would fight
  your manifests (the next `kubectl apply` reverts it) and read-only mode forbids it anyway.
  So on upgrades: read the upstream migration guide, and if a config-level change applies,
  express it in your CR manifests yourself. A practical shortcut: bootstrap the new version in
  write mode against a scratch namespace and diff its CRs against yours. Every CR is stamped
  with the Keycloak version that wrote it, and the server warns at boot about CRs stamped by an
  older version - that's your prompt to check the notes.

## Known limitations

Fine-grained admin permissions v2 needs write mode; switching an area on existing data
is an unassisted migration event; realm renames don't rewrite child CRs; OID4VC and
parameterized scopes are experimental upstream. Details in [ARCHITECTURE.md](ARCHITECTURE.md).

## License

Apache-2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)). The datastore-extension pattern was
inspired by [keycloak-extension-filestore](https://github.com/opdt/keycloak-extension-filestore).
