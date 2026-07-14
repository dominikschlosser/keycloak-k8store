/*
 * Copyright 2026 Dominik Schlosser
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dominikschlosser.k8store.kubernetes;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.keycloak.Config;
import org.keycloak.common.Profile;

/**
 * Configuration of the k8store datastore, read from the {@code datastore/k8store} config scope,
 * i.e. {@code --spi-datastore--k8store--<option>=<value>}.
 *
 * <p>Options:
 *
 * <ul>
 *   <li>{@code read-only} (default {@code true}) - reject all writes to CRD-backed <em>config</em>
 *       areas with {@link org.keycloak.storage.ReadOnlyException}; config CRs are managed
 *       out-of-band. Dynamic areas (sessions etc.), when enabled, stay writable - Keycloak owns
 *       their CRs entirely and logins must work in read-only deployments.
 *   <li>{@code areas} - which storage areas are served from CRs; the rest falls through to
 *       Keycloak's default storage. Values: {@code config} (default, also used when the option
 *       is absent/blank) = the config areas {@code
 *       realm,client,client-scope,role,group,identity-provider}; {@code all} = config areas plus
 *       {@code authorization} (Authorization Services) and {@code organization} (Keycloak
 *       Organizations) - both config-class but opt-in - plus the <em>experimental</em> dynamic
 *       areas {@code
 *       user,user-session,auth-session,login-failure,single-use-object,revoked-token}; or an
 *       explicit comma-separated list of area names.
 *   <li>{@code namespace} - namespace to watch; defaults to the client's current namespace (pod
 *       serviceaccount / kubeconfig context).
 *   <li>{@code all-namespaces} (default {@code false}) - watch CRs cluster-wide.
 *   <li>{@code sync-timeout-seconds} (default {@code 120}) - max time to wait at startup for the
 *       informer caches to sync before failing the boot.
 *   <li>{@code context} - kubeconfig context to connect with; unset (default) means in-cluster
 *       config / current kubeconfig context. Used by the integration tests to pin the kind
 *       cluster.
 *   <li>{@code reconcile-interval-seconds} (default {@code 60}) - periodic list-based mirror
 *       reconciliation; bounds staleness if a watch connection silently stops delivering.
 *       {@code 0} disables it.
 *   <li>{@code expiration-sweep-seconds} (default {@code 300}) - interval of the background
 *       reaper deleting expired CRs of the dynamic kinds; only runs when a dynamic area is
 *       enabled. {@code 0} disables it (reads filter expired entities regardless).
 *   <li>{@code resolve-references} (default {@code false}) - resolve the {@code valuesFrom}
 *       references of config CRs on read. Each entry names a {@code targetPath} and a
 *       {@code valueFrom} source (a Kubernetes {@code secretKeyRef}, {@code configMapKeyRef} or an
 *       inline literal), and its value is injected where a {@code ${...}} placeholder sits in the
 *       string at that path. Off by default so existing CRs are served verbatim. When on, the
 *       backend also watches Secrets and ConfigMaps (needs {@code get,list,watch} on
 *       {@code secrets} and {@code configmaps}). <em>Requires {@code read-only=true}</em>
 *       (validated at boot): resolution is on the read path, so in write mode a saved entity would
 *       persist the resolved value back into the CR and lose the reference. See
 *       {@link io.github.dominikschlosser.k8store.kubernetes.references.ValueReferenceResolver}.
 *   <li>{@code validate-references} (default {@code false}) - validate every config CR's
 *       {@code valuesFrom} references at boot, once the informer caches (including Secrets and
 *       ConfigMaps) have synced, and fail the boot if any is invalid: a malformed entry, a
 *       {@code targetPath} that does not point at a matching placeholder string, or a referenced
 *       Secret/ConfigMap key that is absent. Requires {@code resolve-references}. Off by default,
 *       where references resolve lazily on read and fail open (the placeholder is served verbatim
 *       and logged). Turn it on to catch misconfiguration up front rather than serving unresolved
 *       placeholders.
 *   <li>{@code resources-version-seed} - fixes the theme <em>resources tag</em> used to cache-bust
 *       {@code /resources/{tag}/} URLs. With a relational database and this unset (default), the tag
 *       is the random per-database value Keycloak stores in {@code MIGRATION_MODEL}, which differs
 *       between clusters and across a database reset - during a rolling update a replica reading a
 *       different tag serves 404s for assets whose URLs an in-flight page already rendered. Set to
 *       any stable string, the tag becomes a deterministic hash of {@code (seed, Keycloak version)}:
 *       identical on every replica and redeploy of the same version (so rolling updates keep
 *       in-flight asset URLs valid), and still rotating on a Keycloak upgrade (whose bundled assets
 *       change anyway). When there is <em>no</em> relational database (e.g. the dynamic areas are
 *       served by the Cassandra extension) there is no {@code MIGRATION_MODEL} to fall back to, so an
 *       unset seed derives the tag from the Keycloak version alone. See
 *       {@link io.github.dominikschlosser.k8store.CrMigrationManager} and
 *       {@link io.github.dominikschlosser.k8store.K8sDeploymentStateProviderFactory}.
 * </ul>
 */
public final class K8sStoreConfig {

    public enum Area {
        REALM("realm", false, true),
        CLIENT("client", false, true),
        CLIENT_SCOPE("client-scope", false, true),
        ROLE("role", false, true),
        GROUP("group", false, true),
        IDENTITY_PROVIDER("identity-provider", false, true),
        /**
         * Authorization Services: resource servers, resources, authorization scopes and
         * policies/permissions - configuration-class data that honors read-only mode - plus UMA
         * permission tickets, which are runtime data and stay writable like the dynamic kinds.
         * Not part of the {@code config} default for backward compatibility (deployments that
         * predate this area keep authorization on JPA and their CRD set unchanged); joins
         * {@code all} and explicit lists. Requires the {@code authorization} feature to be
         * enabled (it is by default upstream); pulls in the {@code client} area automatically
         * (resource servers are keyed by their client) - see {@link #directDependencies}.
         */
        AUTHORIZATION("authorization", false, false),
        /**
         * Keycloak Organizations: the organization definitions (name, alias, domains,
         * attributes, redirect URL) plus their invitations. Configuration-class and opt-in like
         * {@link #AUTHORIZATION} - joins {@code all} and explicit lists, never the {@code
         * config} default. Pulls in the {@code group} area automatically (each organization is
         * backed by a group created through {@code session.groups()}, membership is group
         * membership) and the {@code identity-provider} area (the organization linkage lives on
         * the identity provider, stored in the realm CR) - see {@link #directDependencies}.
         * Organization <em>membership</em> lives on the user
         * side and the invitation kind is runtime data - both stay writable in read-only mode;
         * only the organization definitions themselves are read-only config.
         */
        ORGANIZATION("organization", false, false),
        /**
         * Users are a dynamic area even though they look config-ish: self-registration,
         * credential updates, required actions, consents and brute-force lockout flags all
         * mutate users at runtime, so a "read-only users" mode would break logins - user CRs
         * are always writable, like the session kinds.
         */
        USER("user", true, false),
        USER_SESSION("user-session", true, false),
        AUTH_SESSION("auth-session", true, false),
        LOGIN_FAILURE("login-failure", true, false),
        SINGLE_USE_OBJECT("single-use-object", true, false),
        REVOKED_TOKEN("revoked-token", true, false);

        private final String configName;
        private final boolean dynamic;
        private final boolean inConfigDefault;

        Area(String configName, boolean dynamic, boolean inConfigDefault) {
            this.configName = configName;
            this.dynamic = dynamic;
            this.inConfigDefault = inConfigDefault;
        }

        /**
         * Dynamic areas hold volatile per-login state (sessions, login failures, single-use
         * objects, revoked tokens) written by Keycloak at runtime - as opposed to the config
         * areas whose CRs describe realm configuration and are typically managed out-of-band.
         * Dynamic areas are experimental, opt-in ({@code areas=all} or an explicit list) and
         * always writable regardless of read-only mode.
         */
        public boolean isDynamic() {
            return dynamic;
        }

        /**
         * Areas this one cannot function without, because its data lives inside another area's
         * custom resource. {@link K8sStoreConfig#withDependencies} pulls these in transitively
         * rather than rejecting the boot, so e.g. {@code areas=organization} implicitly enables
         * {@code group}, {@code identity-provider} and (through the latter) {@code realm}.
         *
         * <ul>
         *   <li>{@code identity-provider} needs {@code realm}: IdPs are embedded in the realm CR,
         *       so serving them from CRs while realms stay on JPA makes the IdP provider delegate
         *       to the JPA realm adapter, which delegates straight back - infinite recursion.
         *   <li>{@code authorization} needs {@code client}: resource servers are keyed by their
         *       client.
         *   <li>{@code organization} needs {@code group} (every organization is backed by a group
         *       and membership is group membership - Keycloak's JPA organization store even does a
         *       raw {@code em.find(GroupEntity)} on it, so a CR-backed group there NPEs) and
         *       {@code identity-provider} (the organization-to-IdP linkage is stored on the
         *       identity providers in the realm CR).
         * </ul>
         */
        Set<Area> directDependencies() {
            return switch (this) {
                case IDENTITY_PROVIDER -> EnumSet.of(REALM);
                case AUTHORIZATION -> EnumSet.of(CLIENT);
                case ORGANIZATION -> EnumSet.of(GROUP, IDENTITY_PROVIDER);
                default -> EnumSet.noneOf(Area.class);
            };
        }

        static Area fromConfigName(String name) {
            return Arrays.stream(values())
                    .filter(a -> a.configName.equals(name.trim().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown k8store area '" + name + "', supported: "
                            + Arrays.stream(values()).map(a -> a.configName).collect(Collectors.joining(", "))
                            + " (or the shorthands 'config' and 'all')"));
        }
    }

    /**
     * The config areas - the back-compat default when no {@code areas} option is given. Note
     * that this is not "all non-dynamic areas": the {@code authorization} area is config-class
     * but opt-in ({@code all} or an explicit list), so enabling it never surprises existing
     * deployments with new CRD requirements.
     */
    public static Set<Area> configAreas() {
        Set<Area> areas = EnumSet.noneOf(Area.class);
        Arrays.stream(Area.values()).filter(a -> a.inConfigDefault).forEach(areas::add);
        return areas;
    }

    /**
     * Area-set grammar of the {@code areas} option: absent/blank and {@code config} mean the
     * config areas (the back-compat default - dynamic areas never activate implicitly),
     * {@code all} means everything including the experimental dynamic areas, anything else is
     * an explicit comma-separated list of area names.
     */
    static Set<Area> parseAreas(String value) {
        if (value == null || value.isBlank() || "config".equalsIgnoreCase(value.trim())) {
            return configAreas();
        }
        if ("all".equalsIgnoreCase(value.trim())) {
            return EnumSet.allOf(Area.class);
        }
        Set<Area> areas = EnumSet.noneOf(Area.class);
        for (String area : value.split(",")) {
            areas.add(Area.fromConfigName(area));
        }
        return areas;
    }

    /**
     * Expands an area set with its transitive {@linkplain Area#directDependencies dependencies}.
     * An area whose data lives inside another area's custom resource is only useful (and only
     * correct) when that other area is CR-backed too, so we pull the prerequisites in rather than
     * fail the boot: {@code areas=organization} becomes {@code organization, group,
     * identity-provider, realm}.
     */
    static Set<Area> withDependencies(Set<Area> areas) {
        Set<Area> resolved = EnumSet.noneOf(Area.class);
        resolved.addAll(areas);
        Deque<Area> pending = new ArrayDeque<>(areas);
        while (!pending.isEmpty()) {
            for (Area dependency : pending.poll().directDependencies()) {
                if (resolved.add(dependency)) {
                    pending.add(dependency);
                }
            }
        }
        return resolved;
    }

    private static volatile K8sStoreConfig instance;

    private final boolean readOnly;
    private final Set<Area> areas;
    private final String namespace;
    private final boolean allNamespaces;
    private final int syncTimeoutSeconds;
    private final String context;
    private final int reconcileIntervalSeconds;
    private final int expirationSweepSeconds;
    private final String resourcesVersionSeed;
    private final boolean resolveReferences;
    private final boolean validateReferences;

    private K8sStoreConfig(
            boolean readOnly,
            Set<Area> areas,
            String namespace,
            boolean allNamespaces,
            int syncTimeoutSeconds,
            boolean resolveReferences,
            boolean validateReferences) {
        this.readOnly = readOnly;
        this.areas = EnumSet.copyOf(areas);
        this.namespace = namespace;
        this.allNamespaces = allNamespaces;
        this.syncTimeoutSeconds = syncTimeoutSeconds;
        this.context = null;
        this.reconcileIntervalSeconds = 5;
        this.expirationSweepSeconds = 300;
        this.resourcesVersionSeed = null;
        this.resolveReferences = resolveReferences;
        this.validateReferences = validateReferences;
    }

    /** Programmatic configuration for tests, used with {@code K8sStorageBackend.initWithClient}. */
    public static K8sStoreConfig of(
            boolean readOnly, Set<Area> areas, String namespace, boolean allNamespaces, int syncTimeoutSeconds) {
        return of(readOnly, areas, namespace, allNamespaces, syncTimeoutSeconds, false);
    }

    /** Programmatic configuration for tests, with control over reference resolution. */
    public static K8sStoreConfig of(
            boolean readOnly,
            Set<Area> areas,
            String namespace,
            boolean allNamespaces,
            int syncTimeoutSeconds,
            boolean resolveReferences) {
        return of(readOnly, areas, namespace, allNamespaces, syncTimeoutSeconds, resolveReferences, false);
    }

    /** Programmatic configuration for tests, with control over reference resolution and validation. */
    public static K8sStoreConfig of(
            boolean readOnly,
            Set<Area> areas,
            String namespace,
            boolean allNamespaces,
            int syncTimeoutSeconds,
            boolean resolveReferences,
            boolean validateReferences) {
        validateReferenceResolution(readOnly, resolveReferences);
        validateReferenceValidation(resolveReferences, validateReferences);
        K8sStoreConfig config = new K8sStoreConfig(
                readOnly,
                withDependencies(areas),
                namespace,
                allNamespaces,
                syncTimeoutSeconds,
                resolveReferences,
                validateReferences);
        instance = config;
        return config;
    }

    private K8sStoreConfig(Config.Scope scope) {
        this.readOnly = scope.getBoolean("read-only", true);
        this.areas = withDependencies(parseAreas(scope.get("areas")));
        this.namespace = scope.get("namespace");
        this.allNamespaces = scope.getBoolean("all-namespaces", false);
        this.syncTimeoutSeconds = scope.getInt("sync-timeout-seconds", 120);
        this.context = scope.get("context");
        this.reconcileIntervalSeconds = scope.getInt("reconcile-interval-seconds", 60);
        this.expirationSweepSeconds = scope.getInt("expiration-sweep-seconds", 300);
        this.resourcesVersionSeed = scope.get("resources-version-seed");
        this.resolveReferences = scope.getBoolean("resolve-references", false);
        this.validateReferences = scope.getBoolean("validate-references", false);
        validateReferenceResolution(this.readOnly, this.resolveReferences);
        validateReferenceValidation(this.resolveReferences, this.validateReferences);
        validateOrganizationFeatureCoupling(this.areas);
    }

    /**
     * Reference resolution is a read-only-mode feature. Resolution happens on the read path, so in
     * write mode Keycloak re-persists the whole spec on any change (an admin console save maps the
     * full representation back) and a resolved {@code valuesFrom} value would be written into the
     * custom resource in clear, overwriting the reference. Fail the boot rather than silently leak
     * the referenced value back into the CR.
     */
    static void validateReferenceResolution(boolean readOnly, boolean resolveReferences) {
        if (resolveReferences && !readOnly) {
            throw new IllegalArgumentException(
                    "k8store: 'resolve-references' requires read-only mode. Resolution happens on read, so in"
                            + " write mode a saved entity would persist the resolved value back into the custom"
                            + " resource in clear and lose the reference. Either keep"
                            + " --spi-datastore--k8store--read-only=true (the default) or disable"
                            + " --spi-datastore--k8store--resolve-references.");
        }
    }

    /**
     * Startup reference validation only means something when references are resolved. Reject the
     * combination rather than silently ignore {@code validate-references} without
     * {@code resolve-references}.
     */
    static void validateReferenceValidation(boolean resolveReferences, boolean validateReferences) {
        if (validateReferences && !resolveReferences) {
            throw new IllegalArgumentException(
                    "k8store: 'validate-references' requires 'resolve-references'. There is nothing to"
                            + " validate when references are not resolved. Either enable"
                            + " --spi-datastore--k8store--resolve-references or disable"
                            + " --spi-datastore--k8store--validate-references.");
        }
    }

    /**
     * Boot gate for the ORGANIZATION <em>feature</em>: with the feature enabled, groups served
     * from CRs and the {@code organization} area disabled, Keycloak's built-in JPA organization
     * store would serve organizations that reference CR-backed groups - its create path does an
     * {@code em.find(GroupEntity)} on a group row that does not exist and fails with an NPE the
     * moment an organization is created. Failing the boot with a clear message beats a 500 at
     * runtime. Only checked on the server config path (not {@link #of}, which unit tests use
     * without a Keycloak feature profile).
     */
    static void validateOrganizationFeatureCoupling(Set<Area> areas) {
        if (Profile.isFeatureEnabled(Profile.Feature.ORGANIZATION)
                && areas.contains(Area.GROUP)
                && !areas.contains(Area.ORGANIZATION)) {
            throw new IllegalArgumentException(
                    "k8store: the 'organizations' feature is enabled and groups are served from custom"
                            + " resources, but the 'organization' area is not enabled. Keycloak's built-in JPA"
                            + " organization store cannot reference CR-backed groups (organization creation"
                            + " would fail). Either add 'organization' to --spi-datastore--k8store--areas or"
                            + " disable the feature (--features-disabled=organization).");
        }
    }

    /** Resolves (and caches) the configuration from Keycloak's static config. */
    public static K8sStoreConfig get() {
        K8sStoreConfig config = instance;
        if (config == null) {
            synchronized (K8sStoreConfig.class) {
                if (instance == null) {
                    instance = new K8sStoreConfig(Config.scope("datastore", "k8store"));
                }
                config = instance;
            }
        }
        return config;
    }

    /** Test hook: force a re-read of the configuration on next access. */
    public static void reset() {
        instance = null;
    }

    public static boolean isAreaEnabled(Area area) {
        return get().areas.contains(area);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Whether reference placeholders ({@code ${env:...}}, {@code ${secret:...}}) in CR string
     * values are resolved on read. When true the backend also watches Secrets in the write
     * namespace.
     */
    public boolean isResolveReferences() {
        return resolveReferences;
    }

    // When true, every config CR's valuesFrom references are validated at boot and the boot fails
    // on any problem, instead of resolving lazily on read and failing open. Needs resolveReferences.
    public boolean isValidateReferences() {
        return validateReferences;
    }

    // The configured resources-version-seed, or null to use Keycloak's default tag.
    public String getResourcesVersionSeed() {
        return resourcesVersionSeed;
    }

    public Set<Area> getAreas() {
        return areas;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isAllNamespaces() {
        return allNamespaces;
    }

    public int getSyncTimeoutSeconds() {
        return syncTimeoutSeconds;
    }

    /** Kubeconfig context to connect with; null = in-cluster / current context (production). */
    public String getContext() {
        return context;
    }

    /**
     * Interval of the periodic list-based mirror reconciliation that bounds staleness when a
     * watch connection silently stops delivering events; {@code 0} disables it.
     */
    public int getReconcileIntervalSeconds() {
        return reconcileIntervalSeconds;
    }

    /**
     * Interval of the background reaper that deletes expired custom resources of the dynamic
     * kinds (sessions, auth sessions, single-use objects, revoked tokens); {@code 0} disables
     * it. Reads always filter expired entities regardless, so the reaper only bounds garbage
     * accumulation in the cluster.
     */
    public int getExpirationSweepSeconds() {
        return expirationSweepSeconds;
    }
}
