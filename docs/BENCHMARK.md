# Benchmark: k8store vs. vanilla Keycloak

A load-test comparison of the k8store datastore against vanilla Keycloak on the local 2-node
kind cluster, using Keycloak's official load-testing tool
[keycloak-benchmark](https://www.keycloak.org/keycloak-benchmark/) (`kcb.sh`, Gatling-based),
release `999.0.0-SNAPSHOT`. Measured 2026-07-07.

Reproduce with:

```sh
scripts/kind-up.sh
scripts/benchmark.sh
```

## Environment

| | |
|---|---|
| Machine | Apple M5 Max, Docker Desktop VM limited to 18 CPUs / 8 GiB |
| Cluster | kind v0.32.0, Kubernetes v1.36.1, 1 control-plane + 2 workers |
| Keycloak | `quay.io/keycloak/keycloak:nightly` (999.0.0-SNAPSHOT, digest `fe0c86c665fe`, 2026-07-07) |
| Topology | 2 Keycloak replicas (required pod anti-affinity, one per worker), 1 postgres:17 (emptyDir), no resource limits, no metrics-server |
| Load generator | kcb 999.0.0-SNAPSHOT in an `eclipse-temurin:21-jdk` pod pinned to the control-plane node |
| Access path | in-cluster, ClusterIP service `http://keycloak.keycloak:8080` |

The load generator runs **inside** the cluster because on macOS/Docker Desktop the kind node
IPs are not routable from the host (verified - a NodePort would be unreachable), and
`kubectl port-forward` funnels all traffic through a single connection, which distorts load
results. Seeding and sanity checks do use a short-lived port-forward; the measured path does
not.

## Variants

All variants share the same manifests (`deploy/`), the same postgres, the same replica count,
and the exact same base image digest; each starts from a freshly deleted namespace (fresh DB).

| Variant | Image | Server options |
|---|---|---|
| `k8store` | `deploy/Dockerfile` (nightly + provider jars) | `--features=stateless --db=postgres --spi-datastore--provider=k8store ...` - config entities (realm/client/scope/role/group/IdP) served from CR informer mirrors, users/sessions in postgres, write mode |
| `vanilla-stateless` | stock nightly | `--features=stateless --db=postgres` - same feature set, config entities from postgres via JPA (the stateless feature disables the embedded caches), sessions in postgres |
| `vanilla-default` | stock nightly | `--db=postgres` (default features) - embedded Infinispan caches and clustering, as Keycloak is commonly run today; the 2 replicas formed a jdbc-ping cluster (verified 2-member ISPN view) |

`vanilla-stateless` isolates exactly the storage-layer difference under the same feature set;
`vanilla-default` is the conventional baseline.

## Methodology

- Scenarios (kcb):
  - `keycloak.scenario.authentication.ClientSecret` - client-credentials grant: one token
    request per virtual user, at 20 users/sec.
  - `keycloak.scenario.authentication.AuthorizationCode` - browser login: login page GET,
    username/password POST (Argon2 verification), code-for-token exchange, logout
    (4 requests per virtual user), at 8 users/sec (≈ 32 req/s).
- Seeding (identical for every variant): realm `benchmark`, confidential client
  `client-0`/`client-0-secret` (service accounts enabled), 100 users `user-0..user-99` with
  passwords `user-<i>-password` - kcb's default naming conventions, created via admin REST.
- Profile per scenario: one discarded 30 s warm-up pass, then a measured run with 30 s ramp-up
  and 120 s at a fixed arrival rate (open workload model). kcb's `--filter-results` is broken
  in this release (its log processor cannot parse the bundled Gatling's `simulation.log`
  format), so the reported statistics **include the ramp-up phase** - identically for every
  variant. This is also why measured req/s is below the nominal steady-state rate.
- Sanity gate per variant before measuring: client-credentials token request must return 200
  through the in-cluster access path.
- Arrival rates were chosen so that no variant saturates: 0 errors in every cell, and node
  CPU stayed below ~0.3 cores at mid-run `docker stats` samples (no metrics-server in the
  cluster, so no `kubectl top`).
- Single run per cell (see caveats).

## Results

Latencies in milliseconds. 2 700 requests per ClientSecret cell, 4 320 per AuthorizationCode
cell. Zero errors everywhere.

### ClientSecret (client-credentials grant), 20 users/sec

| Variant | req/s | mean | p50 | p95 | p99 | max | errors |
|---|---|---|---|---|---|---|---|
| k8store | 18.1 | 9 | 8 | 13 | 16 | 21 | 0 |
| vanilla-stateless | 18.1 | 7 | 7 | 11 | 14 | 17 | 0 |
| vanilla-default | 18.1 | 7 | 7 | 10 | 12 | 16 | 0 |

### AuthorizationCode (browser login flow), 8 users/sec

| Variant | req/s | mean | p50 | p95 | p99 | max | errors |
|---|---|---|---|---|---|---|---|
| k8store | 29.2 | 10 | 5 | 26 | 31 | 39 | 0 |
| vanilla-stateless | 29.2 | 9 | 4 | 24 | 28 | 39 | 0 |
| vanilla-default | 29.2 | 8 | 4 | 24 | 28 | 40 | 0 |

Per step (mean / p95):

| Step | k8store | vanilla-stateless | vanilla-default |
|---|---|---|---|
| Login page GET | 5 / 7 | 4 / 6 | 4 / 6 |
| Credentials POST | 24 / 30 | 23 / 27 | 22 / 27 |
| Code-for-token exchange | 6 / 7 | 5 / 6 | 3 / 5 |
| Logout | 4 / 6 | 3 / 4 | 3 / 4 |

## Interpretation

- **All three variants are at par in this environment.** Deltas are 1-3 ms on single-digit
  baselines, close to the resolution limit of a shared-laptop setup.
- **k8store shows a consistent ~1-2 ms overhead per request.** Config reads are in-memory
  informer-mirror lookups, but each read hands out a defensive deep copy of the representation
  and goes through the datastore delegation layer - visible when the baseline is a few
  milliseconds, irrelevant once real network/TLS/DB latencies apply.
- **The environment cannot surface the storage-layer difference this benchmark was pointed
  at.** `vanilla-stateless` resolves realm/client from postgres on every request, yet was not
  measurably slower than cache-backed `vanilla-default` - a JPA round-trip to a postgres one
  virtual hop away costs well under a millisecond. The k8store design point (config reads
  never touch the DB, so config traffic does not compete with session/user traffic for DB
  capacity) would only become visible with a contended or remote database, more
  realms/clients, and load levels a laptop cannot generate credibly.
- **The login flow is dominated by Argon2 password verification** (~22-24 ms POST in every
  variant), which is storage-independent. Session writes go to the DB in all three variants
  here (`vanilla-default` keeps them in Infinispan instead) without a visible penalty at
  these rates.

## Caveats - read before quoting numbers

- **kind-on-macOS numbers are not production numbers.** Everything runs in one Docker Desktop
  VM on one laptop; there is no real network, no TLS, and postgres runs on emptyDir.
- **Load generator and servers share the same machine.** The kcb pod is pinned to the
  control-plane node to keep it off the Keycloak workers, but all nodes share the same VM
  CPUs; the Kubernetes API server (which k8store watches) also lives there.
- **Single run per cell, no statistical treatment.** Laptop background load, JIT warm-up
  beyond the single warm-up pass, and Docker Desktop scheduling cause run-to-run variance on
  the order of the observed deltas. A shorter pilot run reproduced the k8store client-secret
  mean within 1 ms, but treat any difference of a few milliseconds as noise.
- **Low arrival rates by design.** The rates were calibrated for zero errors and no CPU
  saturation; this measures per-request latency floors, not throughput ceilings or behavior
  under contention.
- Statistics include the 30 s ramp-up (see methodology), identically for all variants.
- No resource limits on the pods; Keycloak sizes its heap from the node, not a container
  limit. `--hostname-strict=false` and plain HTTP.
- Only two scenarios; admin/CRUD write paths (where k8store writes CRs) and realm import are
  not covered.

Relative comparison between the three variants on the same run day is the only thing these
numbers are good for.
