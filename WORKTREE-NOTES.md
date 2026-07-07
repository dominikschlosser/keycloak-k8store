# Model-layer rewrite — phase 1+2 notes (for phase 3)

Phase 1 replaced the role and group model layers with original, representation-based code and
retargeted the storage backend; phase 2 did the same for clients and client scopes. These notes
record decisions and findings that the remaining phase 3 (realm/identity-provider) must know.
Phase-2-specific notes are in the "Phase 2" section at the end; the phase-1 sections below are
kept as written (with strikethrough-style updates noted inline where phase 2 changed things).

## What phase 1 delivered

* `com.github.dominikschlosser.k8store.crd` — spec classes built on Keycloak's own
  representations: `RealmSpec` (= `RealmRepresentation`, no extra fields), `ClientSpec`,
  `ClientScopeSpec`, `RoleSpec`, `GroupSpec` (each `extends <X>Representation` + `realm` field).
  All five exist; **only `RoleSpec`/`GroupSpec` are wired** (see deviation below).
* `kubernetes/crd/KeycloakRoleCr`/`KeycloakGroupCr` now carry `RoleSpec`/`GroupSpec`.
* `K8sStorageBackend`: `KindState` is generic over any spec type via accessor functions
  (`idFn`/`idSetter`/`realmFn`/`realmSetter`), no `AbstractEntity` bound, no `UpdatableEntity`
  handling. New: every write stamps `keycloak.k8store.io/keycloak-version`
  (`K8sStorageBackend.VERSION_LABEL`); at startup a WARN lists CRs whose stamp differs from the
  running `Version.VERSION` (unstamped/hand-authored CRs are not reported).
* `com.github.dominikschlosser.k8store.spi` — `StoreInvalidation` (shared invalidation enum;
  the old `AbstractK8sProviderFactory.MapProviderObjectType` was removed and all old providers
  were re-pointed at it) and `AbstractCrProviderFactory` (id `k8store`, area gating,
  per-session memoization). New factories extend it; old ones still extend the derived
  `AbstractK8sProviderFactory` until their phases.
* `role/` — `RoleSpec`-based `RoleCrStore`, `RoleAdapter`, `RoleCrProvider(+Factory)`.
  `group/` — `GroupCrStore`, `GroupAdapter`, `GroupCrProvider(+Factory)`.
  All `K8sRole*`/`K8sGroup*`/`AbstractRoleModel`/`AbstractGroupModel` files deleted.

## Deliberate deviation: realm/client/client-scope kinds still use the legacy entities

The phase-1 instruction said to re-register all five kinds on the new specs. That is not
runnable in one phase: the old realm/client/client-scope adapters are hard-wired to the legacy
entity shapes (different field names than the representations), and the CRD schemas prune
unknown fields — a `RealmSpec`-shaped CRD would silently prune every legacy-shaped realm write
and Keycloak could not even boot (no master realm). So `KeycloakRealmCr`, `KeycloakClientCr`
and `KeycloakClientScopeCr` keep their legacy spec entities and CRD schemas until their model
layers are rewritten. The accessor-based `KindState` makes the swap per kind a two-line change
in the backend constructor plus the CR class.

## Findings phases 2/3 will hit

* **`GroupSpec.subGroups` is excluded via an `@JsonIgnore` getter override, not `@SchemaSwap`.**
  Two findings led there:
  1. `@SchemaSwap` matches the *traversed owner type*: on `KeycloakGroupCr`, swapping
     `originalType = GroupRepresentation` only removed the nested occurrence and left the
     top-level `subGroups` in the schema (the top-level bean is `GroupSpec`); swapping
     `originalType = GroupSpec` removed it, and declaring both fails the build with
     "Unmatched SchemaSwaps".
  2. Removing the field from the schema alone is not enough: `GroupRepresentation.getSubGroups()`
     **lazily initializes** the list, so Jackson always serializes `"subGroups": []`
     (`NON_NULL` doesn't drop empty lists) and a real API server rejects server-side apply with
     500 "field not declared in schema". Watch for this hazard with every representation getter
     that lazy-initializes a collection whose field is removed from a schema.
  Consequence for `KeycloakRealmCr` (phase 3): recursion reached through *nested* representation
  fields (`RealmRepresentation.groups` → `GroupRepresentation.subGroups`,
  `ComponentExportRepresentation.subComponents`) swaps on the representation types (the upstream
  keycloak-operator uses `depth = 3` there, which keeps the field in the schema, so lazy-init
  getters are harmless); any recursive field declared on the spec's own inheritance chain needs
  the `RealmSpec`-targeted treatment (or a `@JsonIgnore` override). Also check
  `authorizationSettings` (`ScopeRepresentation.policies`/`resources`) — build and look.
* **Class-level `@JsonInclude(content = NON_NULL)` does not reach map properties inherited from
  the representation superclass.** Null *fields* of the spec are dropped by the class
  annotation, but null map *values* (`attributes: {x: null}`, `ProtocolMapperRepresentation.config`)
  are only dropped by the mapper-level default in `K8sStorageBackend.configureMapper`, which is
  installed into the backend's own `KubernetesClient` (`buildClient`) and used for mirror
  copies. Anything that serializes specs through another mapper (e.g. test fixtures using
  `Serialization.jsonMapper()`) must not put nulls into maps. Server-side apply makes
  null-dropping semantically correct (absent fields are removed).
  **Phase-2 correction:** passing a pre-configured mapper into `new KubernetesSerialization(...)`
  is NOT enough — fabric8's own `KubernetesSerialization.configureMapper` runs in the
  constructor and resets the inclusion default to `(NON_NULL, ALWAYS)` ("omit null fields, but
  keep null map values"), silently undoing the content rule. Phase 1 never noticed because the
  legacy entities carried *property-level* `@JsonInclude(content = NON_NULL)` annotations; plain
  representations don't, and master-realm bootstrap then 422s on the "roles" scope's protocol
  mapper (`"rolePrefix": null`). Fixed by `K8sStorageBackend.buildSerialization()`: a
  `KubernetesSerialization` subclass whose `configureMapper` re-applies ours after fabric8's.
  Phase 3 must keep serializing realm specs through this client — nested representation maps
  (component configs, brute-force maps, `IdentityProviderRepresentation.config`) carry nulls.
* **Spec introspection**: default Jackson bean introspection (getters/setters) is used — the
  representations are proper beans and specs have no write-through setters. No
  `@JsonAutoDetect` needed. `@JsonIgnoreProperties(ignoreUnknown = true)` is on every spec for
  rolling-upgrade tolerance.
* **`suppressingWrites` / the `SUPPRESS_WRITES` ThreadLocal exist only for the legacy
  write-through entity setters.** Once realm/client/client-scope move to representation specs,
  remove the ThreadLocal, `suppressingWrites`, and the `runSuppressed` wrapper in
  `KindState.upsert` (plus its uses in `K8sStorageBackendTest`/`ReadOnlyStorageTest`/
  `CrStoreIsAuthoritativeTest`). Also delete `common/AbstractEntity`, `common/UpdatableEntity`
  and `common/AbstractK8sProviderFactory` when the last legacy package dies.

## Identity & storage semantics (kept / clarified)

* Ids are unchanged and fixed at creation: realm = name, client = clientId, client scope =
  name, realm role = name, client role = `<clientId>:<name>`, group = name (or explicit id).
  Realm `defaultRoleId` references keep working because role ids are unchanged.
* One behavioral cleanup: the old `K8sGroupAdapter.getId()` returned `entity.getName()` (id
  followed renames while the store key did not); the new adapter returns the immutable
  `spec.id`. No test depended on the old behavior.
* Composites and group role grants are stored representation-style **by name**:
  `composites.realm` = realm role names, `composites.client` / `clientRoles` keyed by the
  owning client's id (== clientId). The role-removal cascade rewrites these entries
  (`RoleCrProvider.roleRemoved`, `GroupCrProvider.roleRemoved`). Renaming a role does not
  rewrite name-based references to it — same reference-staleness class as the old id-based
  storage (ids were name-derived), now in standard Keycloak JSON shape.
* Group hierarchy stays flat (`spec.parentId`); `subGroups` is removed from the CRD schema and
  ignored (debug log) if present in a directly-populated spec.
* `searchForGroupByNameStream` keeps the old top-level-only matching semantics (subgroup name
  hits do not surface their root ancestors); revisit if admin-console subgroup search matters.

## Test changes

* `K8sStorageBackendTest`: kept legacy realm/client coverage; added role/group spec coverage
  (metadata-name id defaulting + realm-label fallback, version/realm label stamping, mirror
  isolation, multi-valued attribute round-trip, null-dropping through `configureMapper`).
* Integration tests: CR-*shape* assertions updated to the representation shapes
  (`getRealm()`, `composites.*`, `containerId`/`clientRole`, `realmRoles`,
  `getAttributes().get(...)`); the admin-API-behavior assertions are untouched.
  `ReadOnlyStorageTest`'s GitOps fixture now authors the role as a plain `RoleSpec`.

## Phase 2 — client & client-scope layers (this phase)

### What phase 2 delivered

* `KeycloakClientCr` → `CustomResource<ClientSpec, Void>`, `KeycloakClientScopeCr` →
  `CustomResource<ClientScopeSpec, Void>`; backend registrations swapped to the accessor style
  (client id accessor = `clientId`, scope id accessor = `name`, realm = `spec.realm`,
  metadata.name defaulting + realm-label fallback as for roles/groups).
* `client/{ClientAdapter,ClientCrProvider,ClientCrProviderFactory,ClientCrStore}` and
  `clientscope/{ClientScopeAdapter,ClientScopeCrProvider,ClientScopeCrProviderFactory,
  ClientScopeCrStore}` — original, representation-based, phase-1 pattern (defensive copies,
  explicit persist per mutation; client/scope setters persist only on actual change).
* All `K8sClient*`/`K8sClientScope*`/`AbstractClientModel`/`AbstractClientScopeModel`/
  `K8sProtocolMapper*` files deleted. Only the realm package (+ `identityProvider`,
  `common/{AbstractEntity,UpdatableEntity,AbstractK8sProviderFactory}`) remains legacy for
  phase 3; no other code referenced the deleted packages, so no surgical edits were needed
  there.
* Shared logic lives in `crd/{ProtocolMapperCarrier,ScopeMappingCarrier}` (interfaces both
  specs implement — the representations' inherited accessors already satisfy
  `ProtocolMapperCarrier`) and `common/{ProtocolMapperSupport,ScopeMappingSupport}`.

### Identity conventions (client/scope)

* Client model id == `clientId` (store key, CR-name hash input, `getClientById` ==
  `getClientByClientId`). `spec.id` is kept equal to `clientId`. `setClientId` *moves* the CR:
  delete old key, re-persist under the new one (`ClientAdapter.setClientId`).
* Client scope model id == scope *name* (old adapter already returned the name as id; now the
  store key follows too, so rename = CR move, done in `ClientScopeAdapter.setName`).
  `addClientScope(realm, id, name)` ignores an explicit id != name (debug log) — this store
  uses human-readable ids; duplicate checks still consider the id parameter.
* Realm-level default-scope references (`K8sRealmEntity.defaultClientScopeIds`) hold scope
  getId() values == names; unchanged values at creation, so the legacy realm layer is
  unaffected.

### CR shape changes (tests were adapted accordingly)

* Client default/optional scope assignment: old `spec.clientScopes` map
  (`scopeId -> Boolean`) replaced by the representation's standard
  `defaultClientScopes`/`optionalClientScopes` lists of scope *names*.
* Scope mappings: representations carry none (exports keep them on the realm), so both specs
  add explicit, schema-visible fields (option (a) of the phase brief):
  `realmScopeMappings: [realm role names]` and
  `clientScopeMappings: {ownerClientId: [role names]}` — same by-name convention as the
  phase-1 composites. Role removal cascades rewrite them
  (`ClientCrProvider.roleRemoved`, `ClientScopeCrProvider.roleRemoved` on
  `ROLE_BEFORE_REMOVE`); role *renames* leave stale references (same class as phase 1).
* Protocol mappers: standard `ProtocolMapperRepresentation` lists; ids generated with
  `KeycloakModelUtils.generateId()` at creation, preserved on read; mapper protocol stored,
  falling back to the container protocol (default `openid-connect`) on read.
* Excluded from the client CRD schema via `@JsonIgnore` getter overrides on `ClientSpec`:
  `registeredNodes` (runtime info; kept in the factory's in-memory map like before),
  `access` (per-caller admin permissions), and `authorizationSettings` (served by Keycloak's
  authorization JPA store, and `ResourceRepresentation ↔ ScopeRepresentation` recurse — a CRD
  schema cannot express that). None of these getters lazy-initialize, so the
  `GroupSpec.subGroups` SSA hazard does not apply here. **Phase-3 heads-up:** the realm spec
  embeds plain `ClientRepresentation`s (`RealmRepresentation.clients`), which do NOT get these
  exclusions — `authorizationSettings` recursion must be handled there (e.g. `@SchemaSwap` on
  `ClientRepresentation`/`ResourceServerRepresentation`, upstream keycloak-operator style).
* `registrationAccessToken` (representation name) backs `getRegistrationToken`;
  `adminUrl` backs `getManagementUrl`; `notBefore` is the representation's `Integer` seconds.
  Client secrets stay plaintext in the spec (old behavior).

### Behavior notes / deliberate choices

* Invalidation wiring: `ClientCrProviderFactory` handles `REALM_BEFORE_REMOVE` (bulk CR
  delete, no per-client events — old behavior), `ROLE_BEFORE_REMOVE` (scope-mapping purge),
  `CLIENT_AFTER_REMOVE` (publishes `ClientRemovedEvent`), and — new versus the old layer —
  `CLIENT_SCOPE_BEFORE_REMOVE` purges the removed scope from every client's assignment lists
  (the old layer tolerated stale ids by filtering failed lookups; assignments are now cleaned
  eagerly, and reads still tolerate stale names). `ClientScopeCrProviderFactory` additionally
  handles `ROLE_BEFORE_REMOVE` for scope CRs (the old layer never cleaned those).
  Client removal itself cascades roles via the phase-1 `RoleCrProviderFactory`
  (`CLIENT_BEFORE_REMOVE` → `removeRoles(client)` → per-role `ROLE_BEFORE_REMOVE`).
* `searchClientsByAttributes`/`getClientScopesByAttributes` match attribute values by
  *equality* (upstream JPA semantics); the old derived code used substring `contains` for
  clients. No test exercises the difference.
* `getClientScopes(realm, client, defaultScopes)` resolves names through
  `session.clientScopes().getClientScopesStream(realm)` (one scan, name-keyed) — works
  identically when the client-scope area is JPA-backed (partial areas) because resolution is
  by name, not by store id.
* Scope-assignment protocol filtering kept from the old layer: `addClientScopes` only accepts
  scopes whose protocol equals the client's effective protocol; `getClientScopes` filters the
  same way on read.
* Registered nodes live in `ClientCrProviderFactory.registeredNodesStore`
  (`Map<clientId, Map<node, time>>`), never in CRs; entries are dropped on client removal.

### Test changes (phase 2)

* `K8sStorageBackendTest`: legacy client tests rewritten on `ClientSpec`
  (`ClientCrStore`); added client metadata-name/realm-label defaulting, a client-scope
  mirror round trip (keyed by name, incl. `realmScopeMappings`), and a regression test that
  the *write client's actual serialization* (`buildSerialization().asJson`) drops null map
  values (the fabric8 override trap above).
* Integration tests — CR-shape assertion updates only:
  `getRealmId()` → `getRealm()` everywhere; `spec.clientScopes` map assertions →
  `defaultClientScopes`/`optionalClientScopes` name lists (`ClientScopeParityStorageTest`);
  `spec.scopeMappings` role-id list assertions → `realmScopeMappings` role names /
  `clientScopeMappings[ownerClientId]` (`ScopeMappingParityStorageTest`);
  `K8sProtocolMapperEntity` public-field access → `ProtocolMapperRepresentation` getters
  (`ClientScopeParityStorageTest`, `CrStoreIsAuthoritativeTest`); scope attribute lookup via
  `getAttributes().get(...)`. Admin-API behavior assertions untouched.

### For phase 3

* After the realm layer moves to `RealmSpec`, remove `SUPPRESS_WRITES`/`suppressingWrites`/
  `runSuppressed` (see phase-1 note) — client/scope specs no longer need them; only the realm
  entity's write-through setters still do. Also delete `common/AbstractEntity`,
  `common/UpdatableEntity`, `common/AbstractK8sProviderFactory` with the realm package.
* `ClientScopeCrProviderFactory` still performs the old `CLIENT_SCOPE_BEFORE_REMOVE` →
  `realm.removeDefaultClientScope(scope)` call — that mutates the legacy realm entity; keep
  the equivalent when the realm layer is rewritten (realm-level default scope lists in
  `RealmRepresentation` are name-based: `defaultDefaultClientScopes`/
  `defaultOptionalClientScopes`).
* CRD sizes after phase 2: clients 4.6K, clientscopes 2.1K, groups 1.6K, realms 15.2K
  (legacy), roles 1.9K.

## Phase 3 — realm & identity-provider layers, de-derivation complete (this phase)

### What phase 3 delivered

* `crd/RealmSpec` (extends `RealmRepresentation`) is now the realm CR spec; `spec.realm` (the
  realm name) is the store id, `spec.id` kept equal to it, metadata.name defaulting as for the
  other kinds (id accessor == realm accessor, no realm-label fallback).
* `crd/ClientInitialAccessSpec` — original small shape (id/timestamp/expiration/count/
  remainingCount, seconds; expiration 0 = never) on `RealmSpec.clientInitialAccesses`;
  `RealmSpec.masterAdminClient` is the other original field (exports do not carry the master
  admin client reference, this store serves realms without an import step).
* `realm/{RealmCrStore,RealmAdapter,RealmCrProvider,RealmCrProviderFactory}`,
  `identityProvider/{IdentityProviderCrProvider,IdentityProviderCrProviderFactory}`,
  `CrMigrationManager` (replaces `K8sStoreMigrationManager`), rewritten
  `realm/JpaRealmProviderFactory` (same id `jpa`, `order()` 100, area-gated).
* Deleted: all `realm/K8s*` files (18), `identityProvider/K8sIdentityProvider*` (2),
  `common/{AbstractEntity,UpdatableEntity,AbstractK8sProviderFactory}`,
  `K8sStoreMigrationManager` — plus `SUPPRESS_WRITES`/`suppressingWrites`/`runSuppressed` in the
  backend. `grep -rl "derived from\|keycloak-extension-filestore" core/src/main` is empty; the
  NOTICE derivation paragraph is gone (fabric8 note kept), README/ARCHITECTURE credit the
  filestore extension as the *pattern* inspiration only.

### Realm spec/schema decisions

* **Embedded per-kind collections** (`users`, `federatedUsers`, `clients`, `clientScopes`,
  `roles`, `groups`, `clientScopeMappings`) are **write-only Jackson properties** on
  `RealmSpec`: getter overridden with `@JsonIgnore`, setter re-annotated `@JsonProperty`. The
  crd-generator introspects the serialization side, so they vanish from the CRD schema and from
  every serialization, but still *deserialize* — `RealmSpec.ignoredEmbeddedCollections()` lists
  what arrived and `RealmCrStore` WARNs once per realm (per-kind CRs are the storage). On a real
  API server the schema prunes them before the informer ever sees them; the WARN covers mock
  servers/direct population.
* Fully `@JsonIgnore`d (no warn): `scopeMappings` (no setter on the representation → cannot be
  write-only), deprecated `applications`/`oauthClients`/`clientTemplates`/
  `applicationScopeMappings`/`socialProviders`/`userFederation*`, `organizations`,
  realm-level `protocolMappers`, `adminPermissionsClient` (attribute-backed; embedding a
  `ClientRepresentation` would drag the recursive authorizationSettings graph into the schema),
  legacy key import fields (`privateKey`/`publicKey`/`certificate`/`codeSecret`).
  `clientProfiles`/`clientPolicies` are **field-bound** JsonNodes in the superclass (no
  overridable accessor) — excluded via `@JsonIgnoreProperties(value = {...})` on the class.
* Only one `@SchemaSwap` needed on `KeycloakRealmCr`:
  `ComponentExportRepresentation.subComponents` depth 3 (upstream keycloak-operator value).
  `GroupRepresentation`/`ClientRepresentation` are not traversed (excluded fields), so swaps for
  them would fail the build as unmatched.
* Realm CRD size: 15.2K (legacy) → 21.2K (representation shape).

### RealmAdapter semantics (all mutations persist the whole spec explicitly)

* Realm id == name (`spec.realm`); `setName` MOVES the CR and warns that other kinds' CRs
  referencing the old realm name are not rewritten (rename of a populated realm is effectively
  unsupported — same staleness class as all name-based references).
* Flow bindings (`browserFlow`, ...), execution `flowAlias`/`authenticatorConfig` references
  and IdP `firstBrokerLoginFlowAlias`/`postBrokerLoginFlowAlias` are stored by **alias**
  (export shape); `updateAuthenticationFlow`/`updateAuthenticatorConfig` rewrite all alias
  references on rename.
* **Flow executions live nested in their flow** (`AuthenticationExecutionExportRepresentation`,
  which has no id field). Execution model ids are deterministic:
  `UUID.nameUUIDFromBytes("k8store-execution <flowId> <index>")` — stable across nodes and under
  update/append; a removal shifts ids of later executions in the same flow (admin API re-reads
  after each mutation, whole suite + console flows work).
* Required actions: model id == alias (deterministic); `RequiredActionConfigModel` is a view of
  the action's own `config` map, id == alias (JPA stores them on one row too).
* Components: stored as the export tree (`providerType -> [ComponentExportRepresentation]`,
  children in `subComponents`); flat `ComponentModel` view (parentId) derived on read, tree
  rebuilt on every mutation. Unknown parentIds surface at top level (debug log). Key-provider
  components (incl. private keys) round-trip through the CR — both pods share realm keys.
* Brute-force/OTP/WebAuthn settings are first-class representation fields (tests updated from
  the old attribute-based CR shape); OAuth2 device/CIBA/PAR go through the generic attribute
  API into `spec.attributes` (their `OAuth2DeviceConfig`/`CibaConfig`/`ParConfig` classes read
  realm attributes). `adminPermissionsClientId` and `scimApiEnabled` are attributes as before.
* defaultRole: minimal `RoleRepresentation` (id+name) in `spec.defaultRole`, resolved via
  `session.roles().getRealmRole(realm, name)`. Default groups: **paths**
  (`KeycloakModelUtils.buildGroupPath`/`findGroupByPath`). Default client-scope lists:
  standard `defaultDefaultClientScopes`/`defaultOptionalClientScopes` **names**, resolved
  through `session.clientScopes()` by name (works when the scope area is JPA-backed).
* Required credentials: the representation stores only the type names (`Set<String>`); models
  are resolved from `RequiredCredentialModel.BUILT_IN` (the old layer persisted full entities,
  but the fields always came from BUILT_IN anyway).
* IdP models are built via the provider factory's `createConfig()` (typed config subclasses),
  conversion rep<->model is manual and lenient (stale flow aliases resolve to null instead of
  throwing); IdP removal cascades its mappers; Removed/Updated events published by the adapter
  as before.
* `removeExpiredClientInitialAccess` keeps the read-only guard (no deletes in read-only mode).

### Test updates (CR-shape only)

* `K8sStorageBackendTest`: realm tests on `RealmSpec`/`RealmCrStore`; write-through test
  replaced by an explicit-persist test; new test pinning that embedded collections deserialize
  (for the WARN) but never serialize.
* `RealmConfigStorageTest`: brute-force asserted as first-class fields (was attributes),
  `getRequiredActions()` (was `getRequiredActionProviders()`), executions asserted inside the
  browser flow (was a flat realm-level list), realm CR lookup via `getRealm()`.
* `IdentityProviderParityStorageTest`: `IdentityProviderRepresentation` instead of the entity.
* `ReadOnlyStorageTest` GitOps fixture: plain `RealmSpec` clone, `defaultRole` object instead of
  `defaultRoleId`, no `suppressingWrites`. `CrStoreIsAuthoritativeTest`: components assertion on
  the export tree (`getComponents().values()` flatten); all `suppressingWrites` uses dropped.

### Verification

* `mvn clean install -DskipTests` green; `mvn test -pl core` 15/15; full integration suite
  `mvn test -pl tests` vs kind-k8store: **56/56 green** (first run after the rewrite);
  `scripts/deploy.sh --build` from a clean slate (namespace + CRDs deleted) passes its smoke
  checks; `scripts/e2e.sh -Dtest=WriteModeStorageTest` 7/7.

## Phase D1 — dynamic areas: sessions, auth sessions, login failures, SUO, revoked tokens (this phase)

### What D1 delivered

* New `K8sStoreConfig.Area` values `USER_SESSION/AUTH_SESSION/LOGIN_FAILURE/SINGLE_USE_OBJECT/
  REVOKED_TOKEN` with an `isDynamic()` flag; area grammar now `absent|blank|config` → the six
  config areas (back-compat default), `all` → everything, else explicit list
  (`K8sStoreConfig.parseAreas`, unit-tested). **When USER lands in D2 it joins `all` simply by
  adding the enum value** — parseAreas needs no change.
* Original spec POJOs in `crd/` (no Keycloak representation equivalents exist): `UserSessionSpec`
  (+ embedded `ClientSessionSpec`, map key = client storage id), `AuthSessionSpec` (+ embedded
  `AuthTabSpec`, map key = tab id), `LoginFailureSpec` (id = userId), `SingleUseObjectSpec`,
  `RevokedTokenSpec`. All timestamps epoch-millis `Long` (never `Instant` — schema/serialization),
  `expiresAt` = absolute expiry (null/0 = never). CR classes `Keycloak{UserSession,AuthSession,
  LoginFailure,SingleUseObject,RevokedToken}Cr` (kus/kas/klf/ksuo/krt), same group/version,
  explicit initSpec/initStatus.
* Backend: `KindState` grew `alwaysWritable` + `expiresAtFn`; dynamic kinds are registered ONLY
  when their area is enabled (default deployments boot with zero new watches and don't need the
  new CRDs); `checkWritable` is per-kind (dynamic kinds bypass read-only); every read filters
  expired entities; a reaper (`expiration-sweep-seconds`, default 300, same scheduler thread as
  reconcile) deletes expired CRs. New unbuffered primitives: `updateNow`, `createNow` (atomic
  create, 409 → false), `deleteNow` (boolean = this call deleted), `fetch` (mirror-first read
  with API-server GET fallback by deterministic CR name). SUO/revoked-token kinds use the
  `@global` pseudo-realm via a constant realmFn (realmSetter null is safe because realmFn never
  returns null).
* Providers (all `@AutoService`, id `k8store`, area-gated `isSupported`, `order()` =
  `DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1` = 2, which beats the
  infinispan/persistent session factory's order 1 and the JPA factories' 0):
  `usersession/` (provider + user/client session adapters + `Expirations` helper over
  `SessionExpirationUtils`), `authsession/` (provider + root/tab adapters), `loginfailure/`,
  `singleuse/`, `revokedtoken/`. `K8sDatastoreProvider` overrides `userSessions()/authSessions()/
  loginFailures()/singleUseObjects()/revokedTokens()` gated on the areas.

### Traps found in D1 (D2 will care)

* **Field-level `@JsonInclude(content = NON_NULL)` resets `value` to `ALWAYS`** (annotation
  default), silently overriding the class-level/mapper NON_NULL for that property → a real API
  server 422s on `"userSessionNotes": null`. Class-level `@JsonInclude(value = NON_NULL,
  content = NON_NULL)` on the spec class covers *declared* fields (the phase-2 warning about
  inherited representation maps doesn't apply to original POJOs) — do NOT add field-level
  inclusion annotations.
* **`EnvironmentDependentProviderFactory.isSupported` is evaluated at Keycloak's augmentation**,
  not at runtime. Under `start --optimized` the areas from runtime env are invisible → the
  dynamic factories get pruned and `session.authenticationSessions()` returns null (NPE at
  login). Fix: `deploy/30-keycloak.yaml` now uses a non-optimized `start` that repeats ALL build
  options (`--db=postgres --features=cacheless --spi-datastore--provider=k8store …`) — a
  re-augmenting start derives config from the start command alone (first symptom otherwise:
  "Driver does not support the provided URL" because `--db` fell back to dev-file). Probe
  budgets were raised for the ~1 min re-augmentation. D2's USER area factory will hit the same
  gate — nothing to do beyond keeping the non-optimized start.
* `kubectl apply -f deploy/` does NOT remove env vars added with `kubectl set env` (not in
  last-applied) — remember when flipping areas on the kind cluster.
* `sanitizeLabel` now trims leading/trailing non-alphanumerics ("@global" → "global"); labels
  must start/end alphanumeric.
* Session-ish semantics implemented per the design notes: online/offline sessions are separate
  entities of one kind linked via the `correspondingSessionId` note both ways; TRANSIENT
  sessions live in a per-provider map; providers memoize adapters per id (read-your-write);
  client sessions carry `STARTED_AT_NOTE`/`USER_SESSION_STARTED_AT_NOTE`/remember-me notes so
  the interface's default `getStarted()` etc. work; lightweight users handled via
  `Constants.SESSION_NOTE_LIGHTWEIGHT_USER` + `LightweightUserAdapter.fromString`/update
  handler in both user-session and auth-session adapters.
* SUO/revoked-token providers bypass the tx buffer (visibility + atomicity): `remove` = K8s
  DELETE (exactly-once), `putIfAbsent`/`put` = atomic create with expired-CR overwrite
  fallback; `contains` is mirror-only on purpose (token-validation hot path — no API GET per
  miss; cross-node lag = watch latency).

### Deliberate gaps (documented in ARCHITECTURE "Known limitations")

* Per-session note-based lifespan overrides (`internal.maxLifespanOverride` …) not applied.
* `importUserSessions` keeps the interface's no-op default.
* Auth-session tab limit is the constant 300 (`RootAuthSessionAdapter.TAB_LIMIT`), not an option.
* Login-failure CRs of deleted users linger until realm removal (no per-user-removal hook while
  users are JPA-backed; revisit in D2 where the user provider can cascade).

### D2 (users) handoff

* `UserProvider` + `SubjectCredentialManager` inventory is in the design notes (section 1);
  nightly added abstract OID4VC verifiable-credential methods — stub them (throw) and document.
* Follow the D1 package pattern: `crd/UserSpec` (probably `extends UserRepresentation` like the
  config kinds rather than an original POJO — users HAVE a representation), new Area USER →
  joins `all` automatically, gated KindState registration, factory order PROVIDER_PRIORITY + 1.
* User CRs are dynamic-ish but low-churn: decide writability (probably honor read-only like
  config kinds? or always-writable because of lastLogin-style updates — D1 leaves
  `alwaysWritable` per-kind, so either is a one-flag choice).
* `UserSessionCrProvider.removeUserSessions(realm, user)` and
  `LoginFailureCrProvider.removeUserLoginFailure` are the hooks a D2 user-removal cascade must
  call (upstream `UserManager` does this via `session.sessions()`/`loginFailures()` already).
* The `knownAdapters` per-provider memoization pattern (read-your-write within a session)
  matters even more for users (brute-force + login flows mutate the same user repeatedly).

### D1 verification

* `mvn test -pl core` 30/30 (9 new: area parsing, gated registration, read-only writability
  split, expired-read filtering, reaper, createNow/deleteNow atomicity).
* `mvn test -pl tests` vs kind-k8store: **60/60** (56 existing config-only + 4 new in
  `DynamicAreasStorageTest`: password grant → kus CR with embedded client session + computed
  expirations, admin logout deletes it; auth-endpoint GET (PKCE S256 challenge needed for
  account-console) → kas CR; failed login on brute-force-protected realm → klf CR; token
  revocation → krt CR). SUO CRs are not asserted in integration (no cheap HTTP round trip
  without a full browser login; unit-covered).
* Real cluster: clean-slate `deploy.sh --build` with default areas — smoke green, boot log
  `watching kinds: KeycloakClient, KeycloakClientScope, KeycloakGroup, KeycloakRealm,
  KeycloakRole` (no dynamic informers), password grant leaves zero session CRs; then
  `kubectl set env deployment/keycloak KC_SPI_DATASTORE__K8STORE__AREAS=all` — boot log shows
  all 10 kinds, real curl password grant → `kubectl get keycloakusersessions` shows the session
  CR (embedded admin-cli client session, expiresAt stamped), OIDC logout (204) deletes it.
  Cluster left running with areas=all.

## Phase D2 — dynamic areas: users as custom resources (this phase)

### What D2 delivered

* `Area.USER("user", true)` — dynamic (joins `all`, never in `config`, always writable).
  Rationale documented on the enum: self-registration, credential updates, required actions,
  consents and lockout flags mutate users at runtime, so a "read-only users" mode would break
  logins; the read-only flag keeps guarding config kinds only.
* `crd/UserSpec extends UserRepresentation` + `realm` + one original field
  `federatedIdentityTokens` (map idp-alias → broker token, only written with store-tokens on;
  `FederatedIdentityRepresentation` has no token field). `KeycloakUserCr` (ku,
  `keycloakusers`), gated KindState registration (`alwaysWritable=true`, no expiry).
* `user/{UserCrStore,UserAdapter,UserCrProvider,UserCrProviderFactory,UserSearch}` —
  `UserCrProvider implements UserProvider, UserCredentialStore`; datastore overrides
  `users()`/`userStorageManager()`/`userLocalStorage()` gated on `Area.USER`.

### Identity & normalization decisions

* **User id = lowercased username at creation, immutable afterwards** (role/group convention,
  NOT the client convention): renames keep the id, so token `sub` claims, session CRs and
  login-failure CRs stay valid. `addUser` falls back to `KeycloakModelUtils.generateId()` when
  the natural id is already taken by a since-renamed user; an explicit id parameter is honored.
* **Usernames and emails are stored lowercased** (JPA parity — upstream `UserAdapter` does the
  same via `toLowerCaseSafe`), so every lookup is case-insensitive by construction; no shadow
  field needed. The realm attribute `keycloak.username-search.case-sensitive` is NOT honored
  (documented).
* CR shape: `realmRoles` = realm role names (== ids in this store), `clientRoles` keyed by
  client id (== clientId) with role names, `groups` = **group ids** (deviation from the import
  representation's paths — paths break on parent renames, ids never move), consents as
  `UserConsentRepresentation` (scope names, resolved by name on read, stale names skipped),
  `notBefore` first-class, `serviceAccountClientId` = client id.
* Excluded from schema via `@JsonIgnore` getter overrides: `self`, `origin`, `access`,
  `userProfileMetadata`, `disableableCredentialTypes` (computed), deprecated `totp`/
  `socialLinks`/`applicationRoles`, OID4VC `verifiableCredentials`/`issuedVerifiableCredentials`.
  `AbstractUserRepresentation.getRawAttributes()` lazy-inits but already carries `@JsonIgnore`
  upstream — no SSA hazard. No recursion anywhere in the user representation graph.
  `@SchemaSwap(CredentialRepresentation, "value")` on the CR class prunes the plaintext
  credential field at admission (defense in depth; the write path never sets it).

### Credential wiring (the least-custom path that makes password login work)

* Nightly resolves credentials as: `UserModel.credentialManager()` →
  `session.users().getUserCredentialManager(user)` → concrete
  `org.keycloak.credential.UserCredentialManager(session, realm, user)` (keycloak-model-storage)
  → `getStoreForUser` → `((StoreManagers) datastore).userLocalStorage()` **cast to
  `UserCredentialStore`** for local users (verified in bytecode; `JpaUserProvider` implements
  `UserCredentialStore` for exactly this reason). So `UserCrProvider` implements
  `UserCredentialStore` against `spec.credentials` and everything else — hashing
  (`PasswordHashProvider` via `PasswordCredentialProvider`), validation fan-out, policies —
  is upstream code. NEVER hand-roll hashing.
* `CredentialModel` has no priority field: **the list order of `spec.credentials` IS the
  priority order**; `moveCredentialTo` reorders the list. Stored fields per entry:
  id/type/userLabel/createdDate/secretData/credentialData (manual copy —
  `ModelToRepresentation` REDACTS secretData, don't use it).

### Provider surface notes

* `removeUser` publishes `UserModel.UserPreRemovedEvent` (parity with `UserStorageManager`,
  which we bypass — the authorization store cleans user-owned resources on it);
  `UserRemovedEvent` stays `UserManager`'s job (it publishes after `provider.removeUser`).
* Cascades: factory `InvalidationHandler` for REALM/ROLE/GROUP/CLIENT/CLIENT_SCOPE
  BEFORE_REMOVE (+ equivalent `preRemove` overrides for JPA-served-area paths); IdP removal is
  only announced as `RealmModel.IdentityProviderRemovedEvent` (published by `RealmAdapter`), so
  the factory registers a `postInit` listener for it. New in D2 (fixes a documented D1 gap):
  `UserSessionCrProviderFactory` and `LoginFailureCrProviderFactory` listen for
  `UserModel.UserRemovedEvent` in `postInit` and delete the removed user's session/login-failure
  CRs — regardless of which store serves users.
* Search (`user/UserSearch`, unit-tested): JPA parity — SEARCH terms are prefix matches, `*`
  wildcards, `"quoted"` exact, per-term OR over username/email/first/last; field params infix
  (EXACT → equalsIgnoreCase); service accounts excluded unless INCLUDE_SERVICE_ACCOUNT; unknown
  keys = attribute equality (organizations use this upstream); `UserModel.GROUPS` session
  attribute restricts to group ids (fine-grained admin).
* OID4VC stub policy: `addVerifiableCredential`/`updateVerifiableCredential`/
  `addIssuedVerifiableCredential` throw `UnsupportedOperationException`; the four lookups return
  empty/null; the three removals are no-ops returning false. User-storage federation
  unsupported (datastore returns the provider directly, not `UserStorageManager`) — documented
  in factory javadoc, README, ARCHITECTURE.

### Trap found in D2: embedded test servers share one dev database but not one user store

* The test framework's embedded servers (one per server config, restarted within one JVM) share
  the dev-mem database; the config-mode suites rely on the bootstrap admin (and the temp-admin
  service-account user) surviving there across restarts. An `areas=all` server stores those as
  user CRs instead — with one shared test namespace, whichever mode boots second finds the
  master-realm CR but no admin in its user store, and every admin call 401s
  (`user_not_found: The associated service account for the client does not exist`). Fix:
  `TestKube.dynamicNamespace()` — the dynamic-areas server config gets its own namespace and
  performs its own, fully CR-backed master-realm bootstrap. Remember this when adding any new
  server config that moves the user area.

### Security note (documented in README/ARCHITECTURE, keep in mind)

* User CRs carry credential hashes (`spec.credentials`) and, with token storage, broker tokens
  (`spec.federatedIdentityTokens`) — RBAC read access to `keycloakusers` must be restricted to
  Keycloak's service account. The CRD schema prunes `credentials[].value` (plaintext) at
  admission.

### D2 verification

* `mvn test -pl core` 37/37 (7 new: user-spec round trip with hashed credentials + null-drop,
  excluded-property serialization, hand-authored metadata/label defaulting, user-kind gating,
  user-in-`all` parsing, `UserSearchTest` case-insensitive/JPA-parity semantics ×4).
* `mvn test -pl tests` vs kind-k8store: **65/65** (60 existing + 5 new in `UserAreaStorageTest`:
  admin-created user → ku CR with hashed credentials and no plaintext in the YAML; mixed-case
  password grant works and creates the D1 session CR; admin search/list/count/paging; group
  membership + realm-role grant land on the CR and read back through admin API; user deletion
  deletes the CR).
* Real cluster: clean-slate default deploy — smoke green, no user informer (5 kinds), zero
  `keycloakusers`. Then `kubectl set env ... AREAS=all` — 11 kinds watched, but **admin login
  401s**: flipping the user area orphans the DB-stored bootstrap admin (documented in README/
  ARCHITECTURE as a migration event; the same trap the test framework hit). Re-bootstrapped by
  deleting all k8store CRs + rollout restart → fresh master bootstrap fully CR-backed
  (`master.admin-…` and `master.service-account-temp-admin-…` ku CRs). Admin curl: user create
  201 → `master.smoke-user-…` ku CR with argon2-hashed credentials (zero plaintext occurrences
  in the YAML), mixed-case password grant 200 (+ session CR), user DELETE 204 removes the CR.
  Cluster left running with areas=all.
* CRD sizes after D2: keycloakusers 4.7K; the other ten unchanged.

## Phase A1 — Authorization Services as a CR-backed area (this phase)

### What A1 delivered

* `Area.AUTHORIZATION("authorization", dynamic=false, inConfigDefault=false)` — the enum grew a
  third flag: `configAreas()` is now "areas with inConfigDefault", NOT "non-dynamic areas".
  Authorization is config-class (honors read-only) but opt-in: joins `all` and explicit lists,
  never the `config` default. Validation: requires `Area.CLIENT` (resource-server id == client
  id == clientId).
* Original spec POJOs (no representation reuse — `ResourceRepresentation ↔ ScopeRepresentation
  ↔ PolicyRepresentation` recurse, so representation-extending specs would need heavy pruning):
  `ResourceServerSpec` (realm+clientId+3 settings), `AuthzResourceSpec`, `AuthzScopeSpec`,
  `AuthzPolicySpec`, `PermissionTicketSpec`. Cross-references are plain id sets
  (`scopeIds`, `resourceIds`, `associatedPolicyIds` — the JPA junction tables in CR shape);
  policy provider settings stay in `config` (Keycloak's own JSON-array-string format, owned by
  upstream `RepresentationToModel`/policy providers). CR kinds: `KeycloakResourceServer` (krs),
  `KeycloakAuthzResource` (kazr), `KeycloakAuthzScope` (kazs), `KeycloakAuthzPolicy` (kazp),
  `KeycloakPermissionTicket` (kpt). All five gated on the area in the backend; the ticket kind
  is `alwaysWritable` (UMA runtime data), no kind expires.
* `authz/` package: `AuthzCrStore` (static facade over the five kinds), `CrStoreFactory
  implements StoreFactory` (per-session, holds the SPI's model-level readOnly flag — unrelated
  to k8store read-only, which the backend enforces), five `Cr*Store` SPI implementations,
  five adapters extending Keycloak's `AbstractAuthorizationModel` (gives
  `throwExceptionIfReadonly`), `AuthzCrStoreProviderFactory` (@AutoService
  `AuthorizationStoreFactory`, SPI `authorizationPersister`, id `k8store`, order
  PROVIDER_PRIORITY+1 beating jpa's 1 — default provider of an SPI = highest order()).
  The datastore provider is NOT involved: `AuthorizationProvider` resolves
  `session.getProvider(StoreFactory.class)` itself.

### SPI semantics captured from bytecode (for whoever touches this next)

* Upstream cascades live ABOVE the store in `AuthorizationProvider`'s store wrappers
  (`createPolicyWrapper` etc.): resource delete → tickets + policies-with-only-this-resource;
  scope delete → tickets by scope; policy delete → detach from dependent policies (delete them
  when their association set empties). Do NOT duplicate these in the store.
* `AuthorizationStoreFactory.postInit` default registers synchronization listeners
  (client/realm/user/role/group/organization removed events). `AbstractCrProviderFactory`'s
  no-op postInit SHADOWS that default — the factory overrides postInit to call
  `registerSynchronizationListeners` explicitly. The jpa authz factory's listeners also stay
  registered (both run; the cascades are idempotent and resolve the session-default
  StoreFactory = ours).
* k8store invalidation wiring: CLIENT_BEFORE_REMOVE → delete the client's authz graph
  (tickets→policies→resources→scopes→rs); REALM_BEFORE_REMOVE → bulk delete all five kinds.
  The realm path is needed because k8store realm removal bulk-deletes client CRs without
  per-client events, so upstream's RealmSynchronizer (getClientsStream at RealmRemovedEvent
  time) finds nothing.
* Query semantics mirrored from the JPA named queries: name-unique-per-server enforced at
  create (`ModelDuplicateException` — no DB constraint exists here; upstream relies on unique
  constraints), scope-driven policy lookups restrict `type == 'scope'`,
  `findByScopes(rs, null-resource, scopes)` additionally requires empty resources and NO
  `defaultResourceType` config key, generic policy `find` keeps the implicit owner-is-null
  filter unless OWNER/ANY_OWNER is queried, `findByTypeInstance` = type match + owner !=
  serverId, ticket `find`/`count` carry the caller restriction (non-admin sees only own
  tickets unless caller is the rs's service account). `LikePatterns` grew `like` (case
  sensitive) and `containsTerm` (JPA's escaped `%term%` with `*`→`%`) for the filter options.
* `StoreFactorySpi.isEnabled()` gates on the AUTHORIZATION feature — with the feature disabled
  the SPI vanishes entirely (that, not a boot failure, is why nothing breaks either way; the
  historic reason for disabling the feature in tests could not be reproduced — the suite runs
  green with it enabled).
* The infinispan `authorizationCache` (SPI name `authorizationCache` → option
  `--spi-authorization-cache--default--enabled=false`) wraps the store factory like the realm
  cache wraps realms — disabled everywhere (tests commonOptions, Dockerfile, deploy args) for
  the same informer-is-the-cache reason.

### Trap found in A1 (pre-existing client-area bug, fixed here)

* **Default client scopes were never assigned to clients created through the admin API.**
  Upstream assigns them from `AbstractLoginProtocolFactory`'s `ClientProtocolUpdatedEvent`
  listener, which fires in the MIDDLE of `RepresentationToModel.updateClientProperties`
  (protocol falls back rep→model→"openid-connect", so setProtocol fires on every creation).
  `ClientCrProvider.addClientScopes` used to read a FRESH spec copy, mutate and save it — and
  the in-flight `ClientAdapter` (which persists its own spec instance after every property
  setter) clobbered the assignment with its next persist. Symptom: new clients had only
  event-added scopes (`service_account`), tokens carried `"scope":""`, no `realm_access` — every
  role policy denied, which is how the authz evaluation test caught it. Fix:
  `ClientCrProvider.liveSpec(realm, client)` — cross-cutting writes mutate the adapter's own
  spec instance when the caller holds one (`ClientAdapter.spec()` package accessor);
  `addClientScopes`/`removeClientScope` use it. Remember this pattern for ANY event-listener
  write that targets an entity currently being populated by an adapter.

### Deliberate choices / gaps

* Resource/scope/policy/ticket ids stay generated UUIDs (upstream convention): names are only
  unique per (server[, owner]), and these CRs are Keycloak-managed — human-readable composite
  ids would buy nothing for GitOps authoring of what is effectively engine state.
* Realm-blind SPI vs realm-keyed store: lookups resolve realm from the model instance
  (adapters carry `getRealmId()`), else session context, else a cross-realm mirror scan
  (UUID-unambiguous; resource servers context-first because clientIds repeat across realms).
* FGAP v2 (`admin-permissions`) stays disabled in the default test config (preview; needs
  write mode because it writes policies at runtime — documented in README/ARCHITECTURE). The
  `findDependentPolicies` 6-arg FGAP variant IS implemented (scope-name view/view-members +
  defaultResourceType + associated-policy config LIKE matching) but has no integration test.
* Permission tickets are complete per the SPI (create/find/count/findGranted*/caller
  restriction) but no integration test drives a full UMA ticket flow (requester/grant via
  account console); unit-level coverage only via the writability split test.

### A1 verification

* `mvn test -pl core` 43/43 (6 new in `K8sAuthzKindsTest`: opt-in/`all` grammar,
  requires-client validation, kind gating, read-only config-vs-ticket split, policy spec round
  trip + null-drop, hand-authored rs CR defaulting).
* `mvn test -pl tests` vs kind-k8store: **69/69** (65 existing — now all booting WITH the
  authorization feature enabled — + 4 new in `AuthorizationAreaStorageTest`: enable-authz →
  krs CR + settings update round trip; authz admin API → kazs/kazr/kazp CRs with id
  cross-references; UMA entitlement 200-with-permissions / 403 role-policy split (the
  non-negotiable evaluation path — this test found the default-scope bug); resource-delete and
  client-delete cascades on CRs).
* CRD sizes: keycloakresourceservers 1.2K, keycloakauthzresources 1.6K, keycloakauthzscopes
  1.0K, keycloakauthzpolicies 1.8K, keycloakpermissiontickets 1.2K; the other 11 unchanged.

## Phase A2 — organizations as a CR-backed area (this phase)

### Bytecode findings (nightly, extracted BEFORE coding — the design hangs off these)

* **SPI**: `OrganizationSpi` (name `organization`, gated on `Profile.Feature.ORGANIZATION`),
  resolved via `session.getProvider(OrganizationProvider.class)` (see
  `Organizations.getProvider`) — NOT through the datastore provider, same resolution class as
  the A1 authz store: default provider = highest `order()`; `JpaOrganizationProviderFactory`
  (id `jpa`) has order 0. `OrganizationProviderFactory.isSupported` default = the feature check
  (augmentation-time).
* **Upstream storage model** (`org.keycloak.organization.jpa.*`):
  * `OrganizationEntity` (`ORG` table): id/name/alias/enabled/description/redirectUrl/realmId/
    **groupId** + `OrganizationDomainEntity` set (name, verified). That is ALL the org entity
    holds — everything else lives on other kinds:
  * **Attributes live on the backing group** (`OrganizationAdapter.get/setAttributes`
    delegates to `realm.getGroupById(groupId)`); the attribute search
    (`getAllStream(Map,…)`) joins `GroupEntity.attributes`, `alias` is matched on the entity.
  * **Backing group**: `groupProvider.createGroup(realm, null, GroupModel.Type.ORGANIZATION,
    <orgId>, null)` — group *name* = org id, type = ORGANIZATION, top-level. JPA additionally
    back-links `GroupEntity.organization` (FK) via `em.find(GroupEntity, group.getId())` —
    **this `em.find` NPEs when groups are CRs**, see the feature-gate finding below.
  * **Members = group membership of the backing group**: `user.joinGroup(group,
    new MembershipMetadata(MembershipType.MANAGED|UNMANAGED))`; the type lands in
    `USER_GROUP_MEMBERSHIP.MEMBERSHIP_TYPE`. There is **no model-level API to read the
    membership type back** — `isManagedMember` queries the JPA entity directly. Member reads
    resolve through `userProvider.getUserById`; disabled-org managed members are wrapped in a
    `ReadOnlyUserModelDelegate` (`Organizations.isReadOnlyOrganizationMember`).
  * **IdP linkage lives on the identity provider**: `idp.setOrganizationId(orgId)` +
    `session.identityProviders().update(idp)`; reads via
    `identityProviders().getByOrganization` (a default method over `getAllStream` with the
    `ORGANIZATION_ID` option — `IdentityProviderCrProvider.matches` already implements the
    option, and `RealmAdapter.toIdpModel/toIdpRep` already round-trip
    `organizationId`/`hideOnLogin`; unlinking also removes the `kc.org.domain` config key).
  * **Org-scoped child groups** (nightly grew this): `createGroup(org, id, name, parent)` →
    `Type.ORGANIZATION` groups under the backing group, org FK set; all org-group queries
    filter `type == ORGANIZATION && organization.id == orgId && id != backingGroupId`.
  * **Every realm-group query in `JpaRealmProvider` filters `type == REALM`** (getGroupByName,
    getGroupsStream(realm), the ids+search/ids+paging variants, counts, top-level, search by
    name/attributes) — org groups are invisible to the normal group surface; `getGroupById`
    and `getGroupsStream(realm, ids)` (plain) do NOT filter.
  * **Invitations**: `InvitationManager` (via `provider.getInvitationManager()`) backed by
    `OrganizationInvitationEntity`: id/organizationId/email/firstName/lastName/createdAt/
    expiresAt (epoch SECONDS, `Time.currentTime()` + realm
    `actionTokenGeneratedByAdminLifespan`)/inviteLink. Expired invitations stay queryable
    (STATUS filter EXPIRED) — do NOT wire them into the expiry reaper.
  * `remove(org)`: per member `removeMember` (managed member ⇒ **`removeUser`** — the user's
    lifecycle is bound to the org; unmanaged ⇒ leave org groups + backing group +
    `OrganizationMemberLeaveEvent`), remove backing group, unlink IdPs, fire
    `OrganizationRemovedEvent` (the authz SPI's `OrganizationSynchronizer` — already
    registered by A1's `registerSynchronizationListeners` — cleans FGAP resources), delete
    entity.
  * `create(id, name, alias)`: blank name → `ModelValidationException`; blank alias → alias =
    name after `ReservedCharValidator.validateNoSpace(name)`; duplicate name/alias →
    `ModelDuplicateException`; enabled = true. Alias is **immutable once set** (adapter throws
    on change). `getByDomainName` matches exact + wildcard/parent-domain patterns and picks
    the most specific via `Organizations.resolveByDomain`.
  * The **JPA factory's `postInit` registers a global `GroupEvent` guard**
    (`Organizations.canManageOrganizationGroup` — throws "Can not update organization group"
    unless the session context organization is set or the group is not an org group). It stays
    registered when our factory wins the default slot and resolves the session-default org
    provider, so our provider must do the same `session.getContext().setOrganization(...)`
    dance around group-touching operations that upstream does.
* **Feature-gate finding (requirement 4)**: ORGANIZATION feature on + `group` area in CRs +
  org area OFF is **broken at runtime, not at boot**: the JPA org provider's
  `create`/`createGroup` do `em.find(GroupEntity.class, group.getId())` on a group that only
  exists as a CR → NPE → admin org creation 500s (boot and org-free logins are fine because
  orgs are per-realm opt-in and `hasOrganizations()` is false). Per the phase brief this
  combination is GATED: a clear boot error in `K8sStoreConfig` (Config.Scope path only — NOT
  `of()`, which unit tests use with `Profile` defaults) when the feature is enabled, the
  `group` area is CR-backed and the `organization` area is not. Consequence: the default
  deploy/test config keeps `--features-disabled=organization` (the acceptance test "feature on
  + default areas boots green" is NOT satisfiable — the gate is the honest replacement); the
  feature is enabled together with the area (`OrganizationAreasServerConfig`, areas=all
  deploys).

### Storage design

* `Area.ORGANIZATION("organization", dynamic=false, inConfigDefault=false)` — config-class,
  opt-in exactly like AUTHORIZATION. Validation: requires **GROUP** (backing/org groups,
  membership, attributes-by-convention all go through `session.groups()`) and
  **IDENTITY_PROVIDER** (linkage writes go through `session.identityProviders().update`,
  served from the realm CR; IDENTITY_PROVIDER already requires REALM, so REALM comes
  transitively — no direct realm coupling exists in the bytecode beyond session context).
* `OrganizationSpec extends OrganizationRepresentation` + `realm` + `groupId` (original
  field). **Attributes live in the org spec**, not on the backing group (deviation from JPA —
  the representation carries them, GitOps authors expect them there; the attribute search
  matches spec attributes + the `alias` key). Excluded from the schema, realm-CR
  warn-and-ignore style (write-only Jackson properties + WARN once):
  `members` (membership lives user-side), `groups` (group CRs are the storage; also
  `GroupRepresentation.subGroups` recursion must not enter the schema), `identityProviders`
  (linkage lives on the IdP reps in the realm CR). `domains` stays in the schema — the
  representation has no `setDomains`, so the spec adds one (getter-only collections would
  otherwise hit Jackson's setterless-property path).
* `OrganizationInvitationSpec` — original POJO (id/organizationId/email/firstName/lastName/
  createdAt/expiresAt seconds/inviteLink + realm). Kind `KeycloakOrganizationInvitation`
  (korginv) is **always writable** (runtime data, like permission tickets); NOT wired into
  expiry filtering/reaping (expired invitations remain listable, upstream parity).
* `GroupSpec` grows `type` (`organization`, null = realm group) and `organizationId` —
  the CR shape of `GroupEntity.type` + the organization FK. `GroupCrProvider.createGroup`
  persists the requested type; all realm-group queries now filter org groups out (JPA parity
  list above); duplicate-name checks are scoped per type. `GroupAdapter.getType()` reads the
  spec, `getOrganization()` resolves `organizationId` through the session org provider.
  The org provider stamps `organizationId` through the returned adapter's **live spec**
  (`GroupAdapter.linkOrganization`) — never via a fresh store read (A1 clobbering trap).
* **Membership**: the backing-group membership itself lives wherever users live (JPA
  `USER_GROUP_MEMBERSHIP` or `UserSpec.groups`) — always runtime-writable. The MANAGED marker
  is a **user attribute `k8store.org.managed`** (multi-valued org-id list), uniform across
  both user backends, because no model API can read JPA's `MEMBERSHIP_TYPE` column back and
  an org-CR-side member list would break read-only-mode joins. With the default unmanaged-
  attribute policy the attribute is invisible to and preserved through user-profile updates;
  it dies with the user.
* **Writability split (read-only mode)**: org definitions (korg CR, backing/org group CRs,
  IdP linkage in the realm CR) are config → writes rejected; GitOps authors the korg CR + the
  backing `KeycloakGroup` (type `organization`, name = org id, id = org id per the group id
  convention) + `organizationId` on the realm CR's IdP entry. Membership (join/leave,
  invitation acceptance, broker-driven managed joins) mutates only user-side state + the
  korginv kind → works in read-only mode. Same split class as A1's tickets.
* Cascades: factory handles `REALM_BEFORE_REMOVE` (bulk-delete org + invitation CRs; the
  group CRs die in the group factory's own bulk delete). No `UserRemovedEvent` listener is
  needed — membership and the managed marker live ON the user. Backing-group deletion outside
  the org flow is prevented by upstream's guard listener (see above), matching JPA (which
  guards instead of cascading).

### Handoff for the next phases (organizations DONE in A2; federation/OID4VC/consent-params)

* **Organizations**: delivered in phase A2 below.
* **User-storage federation**: blocked on the datastore returning `UserStorageManager`; if
  tackled, revisit `K8sDatastoreProvider.users()` and the D2 credential-resolution notes.
* **OID4VC verifiable credentials**: `UserCrProvider` stubs throw — the feature needs its own
  storage decision (user-spec field vs own kind).
* **Consent scope-parameters (dynamic scopes)**: still unpersisted in `UserSpec` consents.

## Phase A2 — delivery, traps and verification (design section above)

### What A2 delivered

* `Area.ORGANIZATION("organization", dynamic=false, inConfigDefault=false)`; validation:
  requires GROUP + IDENTITY_PROVIDER (REALM transitively). NEW validation *class*: the boot
  gate `K8sStoreConfig.validateOrganizationFeatureCoupling` runs only on the Config.Scope
  path (never `of()` — unit tests have no feature profile; the gate is unit-tested directly
  with `Profile.init(ProfileName.DEFAULT, Map.of(Feature.ORGANIZATION, ...))` + `Profile.reset()`).
* `crd/OrganizationSpec` (extends `OrganizationRepresentation` + realm + groupId; added
  `setDomains` — the representation is setterless there and Jackson would fail on authored
  domains), `crd/OrganizationInvitationSpec` (original POJO, seconds timestamps),
  `GroupSpec.type`/`organizationId`. CR kinds `KeycloakOrganization` (korg) and
  `KeycloakOrganizationInvitation` (korginv), backend-gated on the area; invitations
  `alwaysWritable`, NOT wired into expiry filtering/reaping (expired stay listable).
* `organization/` package: `OrganizationCrStore` (warn-and-ignore for embedded
  members/groups/identityProviders), `OrganizationInvitationCrStore`, `OrganizationAdapter`,
  `OrganizationInvitationAdapter`, `CrInvitationManager`, `OrganizationCrProvider`
  (per-provider adapter memoization; context-organization dance around every group-touching
  operation), `OrganizationCrProviderFactory` (@AutoService, isSupported = area AND feature,
  order PROVIDER_PRIORITY+1 beats jpa's 0, REALM_BEFORE_REMOVE bulk-deletes org+invitation CRs).
* `GroupCrProvider`: honors `GroupModel.Type` at create (org-scoped subgroups fall back to a
  generated id when the natural name-id is taken — org group names repeat across orgs),
  JPA-parity type filtering (all name/search/count/top-level queries exclude org groups; the
  plain by-ids resolution and `getGroupById`/role-based lookups stay type-blind — including
  the exact JPA quirk that ids+search/ids+paging DO filter), per-type duplicate-name scoping.
  `GroupAdapter.getType()/getOrganization()/linkOrganization(orgId)` (live-spec stamping —
  the A1 anti-clobbering pattern applied at creation time).

### Trap found in A2: the infinispan organization CACHE provider

* Nightly registers `InfinispanOrganizationProviderFactory` under the SAME `organization` SPI
  (id `infinispan`, **order 10** — beats any sane order) and its provider **hardcodes the
  delegate** as `getProvider(OrganizationProvider.class, "jpa")`. First symptom: org creation
  500s with the JPA `em.find(GroupEntity)` NPE even though our factory was registered — the
  cache won default resolution and tunneled straight to JPA. It also dereferences the realm
  cache (`CacheRealmProvider`), which this extension disables. Fix: disable it by config —
  `--spi-organization--infinispan--enabled=false` — exactly the realm-cache/authz-cache
  pattern (tests `K8StoreServerConfig.commonOptions(config, true)`, deploy docs, README,
  ARCHITECTURE). Do NOT try to out-order it: its postInit listeners would still register and
  NPE on group-membership events with the realm cache off.

### Feature-flag decision (requirement 4 — reported)

* The combination "ORGANIZATION feature on + org area off + groups in CRs" is **broken at
  runtime, not boot**: `JpaOrganizationProvider.create` NPEs at `em.find(GroupEntity, ...)`
  (verified live before the fix, see the trap above — same NPE, different route). Per the
  brief it is GATED: boot fails with a clear message when feature+GROUP-area are on and the
  organization area is off. Consequence: **the default deploy/test config keeps
  `--features-disabled=organization`** (the "feature on + default areas boots green"
  acceptance test is unsatisfiable by design — the gate is the honest replacement); the
  feature is enabled together with the area in `OrganizationAreasServerConfig` and in the
  areas=all cluster deployment (which also drops the disable flag and disables the infinispan
  organization cache).

### Read-only / writability decision (requirement 1 — reported)

* Membership lives on the USER side (group membership of the backing group: JPA rows or
  `UserSpec.groups`), so **read-only organizations still allow joins** — no A1-style split of
  one kind was needed; instead the area has one config kind (korg) and one always-writable
  runtime kind (korginv). The MANAGED marker is the user attribute `k8store.org.managed`
  (no model API can read upstream's MEMBERSHIP_TYPE column back; an org-CR-side member list
  would break read-only broker onboarding). GitOps authoring of an org = korg CR + backing
  kg CR (`type: organization`, id/name = org id) + `organizationId` on the realm CR's IdP.

### A2 verification

* `mvn test -pl core` **51/51** (8 new in `K8sOrganizationKindsTest`: opt-in/`all` grammar,
  requires-group+idp validation, feature boot gate (Profile.init-driven), kind gating,
  read-only org-vs-invitation writability split, org spec round trip with domains +
  embedded-collection exclusion + null-drop, group spec type/organizationId round trip,
  hand-authored korg CR defaulting).
* `mvn test -pl tests` vs kind-k8store: **74/74** (69 existing green unchanged + 5 new in
  `OrganizationAreaStorageTest`: org create → korg CR with domains/attributes + backing group
  CR (type organization, invisible to the groups API); member add/remove verified from both
  ends with UNMANAGED type and no member list on the CR; IdP linkage lands as organizationId
  on the realm CR's IdP entry; search by name/domain/exact/attribute; delete cascade removes
  korg + backing group and keeps the unmanaged member user). Invitations have no integration
  test (the invite endpoints send email — no SMTP in the suite); unit + writability covered.
* Real cluster: clean-slate `deploy.sh --build` default deploy — smoke green, 5 config kinds
  watched, no org informer, feature disabled per the gate decision. Then areas=all + feature
  enabled (arg patch replaces `--features-disabled=organization` with
  `--spi-organization--infinispan--enabled=false`, CR wipe + restart re-bootstraps per the D2
  procedure): 18 kinds watched, curl org create 201 → `kubectl get keycloakorganizations`
  shows the korg CR (domains, attributes, groupId) + backing kg CR (type organization),
  member add 201 → members list UNMANAGED, member→organizations lists acme, and the member's
  `keycloakusers` CR carries the backing group id in `spec.groups` (user-side membership
  confirmed live). Cluster left running with areas=all + organizations enabled.
* CRD sizes after A2: keycloakorganizations 1.6K, keycloakorganizationinvitations 1.2K,
  keycloakgroups 1.7K (+2 fields); the other 15 unchanged. 18 CRDs total.

### Handoff for the final phase (federation routing, OID4VC storage, consent scope-params)

* **User-storage federation**: `K8sDatastoreProvider.users()` returns the CR provider
  directly (bypasses `UserStorageManager`) — federation routing means either wrapping the CR
  provider in a `UserStorageManager`-equivalent or reproducing its lookup fan-out; the D2
  credential-resolution notes (UserCredentialManager → userLocalStorage cast) are the
  constraint to keep intact. Note `getByMember`/org membership works through
  `user.getGroupsStream()`, so federated users would join orgs exactly like local ones
  (upstream JPA even has a FederatedUserGroupMembershipEntity path for it).
* **OID4VC verifiable credentials**: `UserCrProvider` stubs still throw; needs a storage
  decision (UserSpec field vs own kind — if own kind, follow the invitation kind: original
  POJO spec, alwaysWritable, area-gated registration).
* **Consent scope-parameters (dynamic scopes)**: still unpersisted in `UserSpec` consents.
* Organization gaps to know about: no integration test drives the invitation flows (SMTP) or
  the org-scoped subgroup admin endpoints (`OrganizationGroupsResource`) — both are
  implemented and unit/parity-reasoned but unexercised end-to-end; org login discovery
  (email-domain → IdP redirect) is implemented via upstream authenticators over
  `getByDomainName` but not integration-tested. `isManagedMember` trusts the
  `k8store.org.managed` attribute without re-checking membership (callers check membership
  first, upstream-shaped); with a permissive unmanaged-attribute policy an admin could edit
  the marker (documented).

## Final phase — federation routing, OID4VC storage, consent scope-params

### Sub-feature 1: user-storage federation with CR users (delivered)

* **Wiring (from nightly bytecode)**: `DefaultDatastoreProvider.userStorageManager()` memoizes
  `new UserStorageManager(session)`; `UserStorageManager.localStorage()` resolves
  `((DefaultDatastoreProvider) session.getProvider(DatastoreProvider.class)).userLocalStorage()`
  — a hard cast to the class, which holds because `K8sDatastoreProvider` extends it. So the fix
  is two overrides: `users()` → `super.userStorageManager()` when the USER area is on (skipping
  the inherited UserCache branch — cacheless disables it anyway), and `userLocalStorage()` →
  the CR provider (unchanged). The `userStorageManager()` override was DELETED (the inherited
  one is now correct). The D2 credential chain is untouched: concrete `UserCredentialManager`
  → `userLocalStorage()` cast to `UserCredentialStore` = the CR provider.
* **Event-ownership change**: `UserStorageManager.removeUser` publishes `UserPreRemovedEvent`
  and THEN calls `localStorage().removeUser` — `UserCrProvider.removeUser` therefore no longer
  publishes it (D2 had it publish because the manager was bypassed; keeping it would fire the
  authz cleanup twice). The service-account fallback removal in `clientRemoved` now goes
  through `session.users().removeUser(...)` so the event still fires on that path.
* `removeImportedUsers`/`unlinkUsers` delegate manager → localStorage 1:1 (verified in
  bytecode); no FEDERATION_LINK search param exists on nightly (checked all storage jars — the
  imported-user maintenance surface is exactly those two methods + `user.isFederated()` =
  federationLink not blank in `validateUser`). `UserSpec.federationLink` was already in the
  schema (never excluded). New guard in `addUser`: a natural id that parses as a federated
  `StorageId` ("f:…") falls back to a generated id — the manager would misroute such ids.
* **Test-provider deployment finding**: the embedded server does NOT see the test classpath.
  `KeycloakServerConfigBuilder.dependencyCurrentProject()` deploys `target/classes` of the
  current maven module (via `MavenProjectUtil.getCurrentModule().getClassesDir()`), NOT
  test-classes — so the tiny import-style `TestFederationUserStorage` provider lives in the
  tests module's `src/main/java` (+ `META-INF/services` in `src/main/resources`, provided-scope
  Keycloak deps in the tests pom), and `DynamicAreasServerConfig` adds
  `dependencyCurrentProject()`. Import trap: use `addUser(realm, null, username, true, FALSE)`
  and set email/emailVerified/first/last — default required actions or an incomplete profile
  fail direct grants with "Account is not fully set up" (`resolve_required_actions`).
* `UserFederationStorageTest` (2 tests): `session.users()` is a `UserStorageManager`
  (run-on-server); full import lifecycle — federated login imports a ku CR with
  `federationLink` = component id and NO credentials (validation stays federated), wrong
  password 400, admin search sees the imported user, `unlink` clears the link and keeps the
  CR, `remove-imported-users` deletes the CR.
* Gates: core 51/51, integration 76/76 (74 + 2).

### Sub-feature 2: OID4VC verifiable-credential storage (delivered)

* **Surface re-verified via javap** (nightly): `UserProvider` grew
  `addVerifiableCredential(userId, model)` / `updateVerifiableCredential(userId, X)` /
  `removeVerifiableCredential(userId, X)` / `getVerifiableCredentialsByUser` (sorted by
  clientScopeId) / `ById` / `ByClientScope` + the issued quartet. **TRAP: the second parameter
  of update/remove is the CLIENT SCOPE id, not the credential id** — the JPA filter lambdas
  compare `entity.getClientScopeId()` and the admin resource passes `scope.getId()` (found
  live: the admin PUT 404ed against an id-based filter). One credential per (user, scope).
* **Entities extracted from bytecode**: `UserVerifiableCredentialEntity`
  (id/user/clientScopeId/revision/userAttributes JSON/created/updated + optimistic version) and
  `IssuedVerifiableCredentialEntity` (id/user/verifiableCredentialId/issuedAt/expiresAt/
  clientId/revision). Semantics mirrored: ids generated (`generateId` for credentials,
  `SecretGenerator.generateSecureID` for issuances and all revisions), attribute snapshot
  falls back to `UserProfileProvider.create(USER_API, user).getAttributes().getReadable()`,
  update rolls revision + refreshes snapshot, issuance inherits the referenced credential's
  revision (ModelException when absent), `removeVerifiableCredential` deletes the user's
  issuances of it; cascades: realm/user/scope (+ issuances via scope's credentials)/client
  (issued-by-client) — all mirrored from the JPA named queries.
* **Storage decision: own kinds (option b)** — `KeycloakUserVerifiableCredential` (kuvc) and
  `KeycloakIssuedVerifiableCredential` (kivc), original POJO specs, alwaysWritable, registered
  gated on `Area.USER && Profile.isFeatureEnabled(OID4VC_VCI)` (feature-off deployments need
  neither CRDs nor watches; the Profile check is null-safe for unit tests — Profile.getInstance()
  is NULL when never initialized, isFeatureEnabled NPEs). The SPI is realm-blind: realm resolves
  from the session context (issuance/admin always have one), by-id lookups fall back to a
  cross-realm scan (generated ids, A1 pattern).
* **Expiry finding**: upstream cleans expired issued VCs via the scheduled task
  `ClearExpiredIssuedVerifiableCredentials`, registered in
  `DefaultDatastoreProviderFactory.postInit` → `setupScheduledTasks` — which NEVER RUNS under
  k8store (our factory replaces postInit; nothing else calls setupScheduledTasks — verified
  across all jars). This also means the OTHER upstream scheduled cleanups (expired events etc.)
  don't run under this datastore — pre-existing, out of scope, worth knowing. Consequence:
  kivc wires `expiresAtFn` (read filtering + D1 reaper = the replacement);
  `removeExpiredIssuedVerifiableCredentials` is a documented no-op. Deviation: expired
  issuances turn invisible immediately instead of lingering until a cleanup run.
* Feature maturity: OID4VC_VCI and PARAMETERIZED_SCOPES are both EXPERIMENTAL upstream.
  The admin surface lives under `users/{id}/vc` (`credentials`, `credentials/{scopeName}`,
  `issued-credentials`); `createCredential` needs a client scope with protocol `oid4vc` and
  `realm.verifiableCredentialsEnabled=true` (first-class on the realm spec already).
* Tests: `K8sVerifiableCredentialKindsTest` (3: double gating incl. feature-off/area-off,
  read-only writability + null-free round trip, issued expiry filter + reaper);
  `Oid4vcAreaStorageTest` (2, `DynamicAreasServerConfig` now enables OID4VC_VCI +
  `dependencyCurrentProject`): admin `users/{id}/vc/credentials` lifecycle → kuvc CR with
  snapshot/revision roll on PUT/delete on revoke; issuance SPI via run-on-server (a full wallet
  flow needs key material — disproportionate) → kivc CR with inherited revision + expiresAt,
  credential removal takes issuances along.
* Gates: core 54/54, integration 78/78 (76 + 2). CRDs regenerated: kuvc 1.3K, kivc 1.2K,
  existing schemas unchanged.

### Sub-feature 3: consent scope-parameters (delivered — the model surface EXISTS on nightly)

* **Model finding**: nightly's `UserConsentModel` DOES carry parameters — a
  `MultivaluedHashMap<String,String>` keyed by scope **id**, `addGrantedClientScope(scope,
  parameter)` (throws for a parameterized scope with null parameter), `getParameters(scope)`,
  `isClientScopeGranted(scope, parameter)`. The feature was RENAMED upstream: dynamic-scopes →
  `PARAMETERIZED_SCOPES` (experimental); scope markers are the attributes
  `is.parameterized.scope` + `parameterized.scope.type` (provider ids: string/custom/boolean/
  integer/username/delegation; custom additionally needs `parameterized.scope.regexp`) —
  creation VALIDATES the type ("Parameterized scope must have a parameter type", found live).
  JPA persists one `UserConsentClientScopeEntity` row per (scope, parameter); non-parameterized
  rows store the `#N A#` placeholder; a parameterized scope granted without parameters gets NO
  row (= not granted on read) — mirrored.
* CR shape: `UserConsentSpec extends UserConsentRepresentation` +
  `grantedScopeParameters: map<scope name, [parameters]>` (only parameterized scopes appear;
  scope id == name here). `UserSpec` swaps the inherited representation-typed `clientConsents`
  property for the typed one under the SAME JSON name (`@JsonIgnore` both inherited accessors,
  `@JsonProperty("clientConsents")` on the new pair) — existing CRs parse unchanged, the
  keycloakusers schema gains one optional field (compatible per crd-diff classes).
* Read side is lenient like the rest of the consent handling: stale scope names skipped, a
  parameterized scope without stored parameters counts as not granted (JPA-equivalent);
  client-scope removal cascade also purges the scope's parameter entries.
* The admin consent REST representation has NO parameter surface upstream — integration is
  driven through the SPI via run-on-server (`ConsentParametersStorageTest`: grant with two
  parameters + a plain scope → `grantedScopeParameters` on the ku CR; model read-back with
  `getParameters`/`isClientScopeGranted` positive+negative; update replaces the parameter set).
  `DynamicAreasServerConfig` enables PARAMETERIZED_SCOPES (+ OID4VC_VCI from sub-feature 2).
* Gates: core 55/55, integration 79/79 (78 + 1).

### Final-phase verification and wrap-up

* `mvn test -pl core` **55/55**; `mvn test -pl tests` vs kind-k8store **79/79**
  (74 pre-phase + 2 federation + 2 OID4VC + 1 consent-parameters).
* Real cluster: clean-slate `deploy.sh --build` default deploy — smoke green, 5 config kinds
  watched, no dynamic informers, zero `keycloakusers`. Then areas=all (args patched to
  `--features=cacheless,organization,oid4vc-vci,parameterized-scopes` +
  `--spi-organization--infinispan--enabled=false`, AREAS=all env, CR wipe + restart per the D2
  re-bootstrap procedure): **20 kinds watched**, CR-backed admin grant OK, user create 201 →
  hashed ku CR (no plaintext), mixed-case grant 200 + session CRs, OID4VC end-to-end over the
  REAL API server (realm `verifiableCredentialsEnabled` PUT 204 → oid4vc scope 201 → admin
  `users/{id}/vc/credentials` POST 200 → kuvc CR schema-validated with revision/snapshot,
  revoke 204 deletes it), user DELETE 204 removes the CR. Cluster left running with areas=all
  and the three features enabled.
* Cosmetic fix surfaced by the new kinds: the boot log derived kind names with
  `replace("Cr", "")`, mangling `...CredentialCr` to `...edential` — now strips only the
  two-character suffix.
* CRD inventory: **20 CRDs** — realms 21.2K, clients 4.6K, users 4.9K (+grantedScopeParameters),
  usersessions 2.3K, authsessions 2.3K, clientscopes 2.1K, roles 1.9K, authzpolicies 1.8K,
  groups 1.7K, authzresources 1.6K, organizations 1.6K, kuvc 1.3K, permissiontickets 1.2K,
  resourceservers 1.2K, orginvitations 1.2K, kivc 1.2K, loginfailures 1.2K, authzscopes 1.0K,
  singleuseobjects 0.9K, revokedtokens 0.8K.
* Docs state: README/ARCHITECTURE now document federation as supported (import-style shadow
  users as CRs), OID4VC storage (two opt-in-by-feature CR kinds, experimental caveat, reaper
  replaces the never-registered upstream cleanup task) and consent scope-parameters
  (grantedScopeParameters on the consent entries); the known-limitations lists were updated
  accordingly, and the config references mention the two optional experimental features.
* Open (documented) leftovers: upstream scheduled cleanup tasks in general do not run under
  this datastore (only the issued-VC one matters for CR kinds — covered by the reaper);
  remote-mode (`KC_TEST_SERVER=remote`) runs of the three new tests need the tests module's
  provider jar on the remote server for the federation test (embedded is the tested mode).
