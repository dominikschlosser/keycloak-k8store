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
package com.github.dominikschlosser.k8store.kubernetes;

import java.util.Arrays;
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
 *   <li>{@code resources-version-seed} - fixes the theme <em>resources tag</em> used to cache-bust
 *       {@code /resources/{tag}/} URLs. Unset (default), the tag is the random per-database value
 *       Keycloak stores in {@code MIGRATION_MODEL}, which differs between clusters and across a
 *       database reset - during a rolling update a replica reading a different tag serves 404s for
 *       assets whose URLs an in-flight page already rendered. Set to any stable string, the tag
 *       becomes a deterministic hash of {@code (seed, Keycloak version)}: identical on every replica
 *       and redeploy of the same version (so rolling updates keep in-flight asset URLs valid), and
 *       still rotating on a Keycloak upgrade (whose bundled assets change anyway). See
 *       {@link com.github.dominikschlosser.k8store.CrMigrationManager}.
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
         * enabled (it is by default upstream) and the {@code client} area (resource servers are
         * keyed by their client).
         */
        AUTHORIZATION("authorization", false, false),
        /**
         * Keycloak Organizations: the organization definitions (name, alias, domains,
         * attributes, redirect URL) plus their invitations. Configuration-class and opt-in like
         * {@link #AUTHORIZATION} - joins {@code all} and explicit lists, never the {@code
         * config} default. Requires the {@code group} area (each organization is backed by a
         * group created through {@code session.groups()}, membership is group membership) and
         * the {@code identity-provider} area (the organization linkage lives on the identity
         * provider, stored in the realm CR). Organization <em>membership</em> lives on the user
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

    private K8sStoreConfig(
            boolean readOnly, Set<Area> areas, String namespace, boolean allNamespaces, int syncTimeoutSeconds) {
        this.readOnly = readOnly;
        this.areas = EnumSet.copyOf(areas);
        this.namespace = namespace;
        this.allNamespaces = allNamespaces;
        this.syncTimeoutSeconds = syncTimeoutSeconds;
        this.context = null;
        this.reconcileIntervalSeconds = 5;
        this.expirationSweepSeconds = 300;
        this.resourcesVersionSeed = null;
    }

    /** Programmatic configuration for tests, used with {@code K8sStorageBackend.initWithClient}. */
    public static K8sStoreConfig of(
            boolean readOnly, Set<Area> areas, String namespace, boolean allNamespaces, int syncTimeoutSeconds) {
        validateAreas(areas);
        K8sStoreConfig config = new K8sStoreConfig(readOnly, areas, namespace, allNamespaces, syncTimeoutSeconds);
        instance = config;
        return config;
    }

    private K8sStoreConfig(Config.Scope scope) {
        this.readOnly = scope.getBoolean("read-only", true);
        this.areas = parseAreas(scope.get("areas"));
        this.namespace = scope.get("namespace");
        this.allNamespaces = scope.getBoolean("all-namespaces", false);
        this.syncTimeoutSeconds = scope.getInt("sync-timeout-seconds", 120);
        this.context = scope.get("context");
        this.reconcileIntervalSeconds = scope.getInt("reconcile-interval-seconds", 60);
        this.expirationSweepSeconds = scope.getInt("expiration-sweep-seconds", 300);
        this.resourcesVersionSeed = scope.get("resources-version-seed");
        validateAreas(this.areas);
        validateOrganizationFeatureCoupling(this.areas);
    }

    /**
     * Identity providers are embedded in the realm entity: serving them from CRs while realms
     * stay on JPA would make the IdP provider delegate to the JPA realm adapter, which delegates
     * straight back to the IdP provider - infinite recursion. Fail fast instead.
     */
    private static void validateAreas(Set<Area> areas) {
        if (areas.contains(Area.IDENTITY_PROVIDER) && !areas.contains(Area.REALM)) {
            throw new IllegalArgumentException(
                    "k8store: the 'identity-provider' area requires the 'realm' area (identity providers are"
                            + " embedded in the realm custom resource)");
        }
        if (areas.contains(Area.AUTHORIZATION) && !areas.contains(Area.CLIENT)) {
            throw new IllegalArgumentException(
                    "k8store: the 'authorization' area requires the 'client' area (resource servers are keyed"
                            + " by their client)");
        }
        if (areas.contains(Area.ORGANIZATION) && !areas.contains(Area.GROUP)) {
            throw new IllegalArgumentException(
                    "k8store: the 'organization' area requires the 'group' area (every organization is backed"
                            + " by a group and membership is group membership)");
        }
        if (areas.contains(Area.ORGANIZATION) && !areas.contains(Area.IDENTITY_PROVIDER)) {
            throw new IllegalArgumentException(
                    "k8store: the 'organization' area requires the 'identity-provider' area (the organization"
                            + " linkage is stored on the identity providers in the realm custom resource)");
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
