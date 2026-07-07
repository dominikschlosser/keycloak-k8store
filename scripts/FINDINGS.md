# Findings from local kind verification

Verified against `quay.io/keycloak/keycloak:nightly` (Keycloak `999.0.0-SNAPSHOT`, Quarkus
3.33.2.1), kind v0.32.0, 2-worker cluster from `scripts/kind-up.sh`. Last full clean-slate
verification: 2026-07-06 (fresh namespace + CRDs + DB, image rebuilt from the current core).

Status: findings 1-3 and 5 are **fixed in core and re-verified**; finding 4 remains a
**known limitation**.

## 1. FIXED — provider packaging clashed with Keycloak's Quarkus classloader

History: the original shaded fat jar bundled unrelocated Vert.x/Netty and `kc.sh build`
failed with `LinkageError: loader constraint violation ... io.vertx.core.Handler`. An interim
fix relocated Vert.x/Netty into a `-dist` classifier jar; the final fix removed shading
entirely: the core build now stages the thin `keycloak-k8store-<version>.jar` plus its
runtime dependency jars (fabric8 kubernetes-client with **kubernetes-httpclient-jdk** — no
Vert.x, no Netty, no relocation; 32 jars) in `core/target/providers/`, and
`deploy/Dockerfile` COPYs `core/target/providers/*.jar` into `/opt/keycloak/providers/`.

**Verified:** `kc.sh build` passes with the multi-jar layout (`Quarkus augmentation completed
in 3075ms`), no duplicate-Jackson complaints even though jackson-core/databind/annotations
land in `providers/` alongside Keycloak's own copies; runtime is clean (no classloading
errors in the pod logs).

## 2. FIXED — feature name is `organization`, not `organizations`

`deploy/Dockerfile` uses `--features-disabled=organization` (singular).

## 3. FIXED — CRD 422 on `null` protocol-mapper config value (crashed bootstrap, broke realm creation)

Root cause (as diagnosed here earlier): `K8sProtocolMapperEntity.config` lacked a
**field-level** `@JsonInclude(content = NON_NULL)` — the class-level annotation does not
apply to Map contents — so `usermodel.realmRoleMapping.rolePrefix -> null` was serialized as
JSON `null` and the CRD schema rejected the write with 422, crashing the first boot
(half-written master realm) and turning every `POST /admin/realms` into an HTTP 500.
Fixed in core by annotating all map-typed entity fields at field level (plus a unit test
pinning the repro).

**Verified on a clean slate:** first boot bootstraps master fully with **zero container
restarts**; the master realm CR has `spec.enabled: true` and all lifespans; the bootstrap
admin user and the `temp-admin` service client are created by the normal boot;
`POST /admin/realms {"realm":"demo","enabled":true}` returns **201** and the demo realm CR
is complete (`spec.enabled: true`). All bootstrap-repair workarounds have been removed from
`scripts/deploy.sh`.

## 4. KNOWN LIMITATION — non-transactional multi-CR writes leave partial state on any failure

One Keycloak transaction fans out into many CR writes executed sequentially at flush time.
Any rejected write (schema validation, RBAC, conflict) aborts the JTA transaction — the
admin API returns 500 and the DB side rolls back — but the CRs already applied stay; there
is no compensation/retry. Harmless for GitOps-authored CRs (read-only mode); in read-write
mode every failed admin write is a potential source of drift between DB and CRs.

## 5. FIXED — nested-entity updates (component config, protocol mappers, ...) were never persisted to the parent CR

Symptom found here: on a cleanly bootstrapped realm the `declarative-user-profile`
component's `kc.user.profile.config` was missing from the realm CR (Keycloak writes it via a
component *update* after creation), so profile validation fell back to the built-in schema
that requires email/name — `VerifyUserProfile` then added VERIFY_PROFILE to the email-less
bootstrap admin and every admin/admin password grant failed with
`error="resolve_required_actions", "Account is not fully set up"`. Root cause in core: the
vendored adapters mutated nested entities (components, identity providers, flows, protocol
mappers, ...) with plain setters without persisting the parent CR; all 11 nested-update
paths now persist explicitly, with integration regression tests.

**Verified on a clean slate (plain deploy, zero manual repairs):** admin/admin password
grant succeeds immediately after `scripts/deploy.sh`, and the master realm CR's
`declarative-user-profile` component contains `kc.user.profile.config`.

## Remote-mode test caveats (not bugs; expected config mismatches)

`scripts/e2e.sh` (KC_TEST_SERVER=remote, full suite) against the write-mode all-areas
cluster: **WriteModeStorageTest 7/7 pass**. The other two classes fail for exactly the
expected configuration reasons, not because of extension bugs:

* `ReadOnlyStorageTest.configWritesAreRejected` — expects a `WebApplicationException` on
  config writes; the cluster runs `KC_SPI_DATASTORE__K8STORE__READ_ONLY=false`, so the write
  succeeds (3/4 of the class pass, including out-of-band CR visibility). Re-run against
  `scripts/deploy.sh --read-only true` to exercise this class.
* `PartialAreasStorageTest.realmsAreCustomResourcesButGroupsStayInDatabase` — expects a
  server configured with a partial `areas` subset (groups in JPA); the cluster serves all
  areas from CRs, so a KeycloakGroup CR IS created.

For the remote pass use `scripts/e2e.sh -Dtest=WriteModeStorageTest` (verified green).

## Environment note (not a bug)

`cacheless` requires JDBC_PING; the nightly derives it automatically (startup log: `JGroups
JDBC_PING discovery enabled.` / `Starting JGroups channel ISPN with stack jdbc-ping`) — no
`KC_CACHE_STACK` needed. The two replicas form the expected 2-node cluster view.
