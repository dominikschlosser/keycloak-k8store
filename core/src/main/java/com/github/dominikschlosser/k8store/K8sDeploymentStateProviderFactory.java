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
package com.github.dominikschlosser.k8store;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.google.auto.service.AutoService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.Version;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.migration.MigrationModel;
import org.keycloak.models.DeploymentStateProvider;
import org.keycloak.models.DeploymentStateProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.jpa.MigrationModelAdapter;
import org.keycloak.models.utils.PostMigrationEvent;

/**
 * Owns the theme <em>resources tag</em> (the {@code /resources/{tag}/} cache-buster). Overrides
 * Keycloak's built-in {@code jpa} deployment-state provider - same id, higher {@link #order()} - so
 * this one is selected.
 *
 * <p>With a relational database its {@link MigrationModel} delegates to the JPA-backed adapter, so
 * the stored-version bookkeeping the JPA store (and {@link CrMigrationManager}) relies on is
 * unchanged and only the resources tag differs, and only when configured. Keycloak's JPA connection
 * provider publishes {@link Version#RESOURCES_VERSION} (which the resource-URL builder and the
 * static-resource route both read) from the {@code MIGRATION_MODEL} tag via a raw SQL read on every
 * boot - it bypasses this SPI (so overriding {@link DeploymentStateProvider#getMigrationModel} does
 * not influence it) and runs after other providers' {@code postInit} (so setting the static there is
 * clobbered). Keycloak's bootstrap fires {@link PostMigrationEvent} after every provider's
 * {@code postInit}, so re-applying the seeded tag from that listener wins regardless of ordering.
 *
 * <p><b>No relational database.</b> When k8store is paired with a non-JPA store for the dynamic
 * areas (for example the Cassandra extension serving users/sessions), there is no
 * {@link JpaConnectionProvider} and nothing owns the {@code MIGRATION_MODEL} table. This provider
 * then serves a database-free {@link DbLessMigrationModel} whose resources tag is derived from the
 * {@code resources-version-seed} - or, when that is unset, from the Keycloak version alone (a
 * deterministic value that is identical on every replica and rotates on a Keycloak upgrade, which is
 * all the tag needs). {@link CrMigrationManager} consumes that model unchanged: the stored version
 * reads as absent (its version check is a harmless no-op) and the seed-derived tag is published.
 *
 * <p>When {@code resources-version-seed} is set the tag is a hash of {@code (seed, Keycloak version)}:
 * identical on every replica and redeploy of the same version - so a rolling update keeps in-flight
 * asset URLs valid - and still rotating on a Keycloak upgrade whose bundled assets change. With a
 * relational database and no seed the random per-database tag is left in place. Mirrors
 * keycloak-cassandra-extension's {@code resourcesVersionSeed}.
 */
@AutoService(DeploymentStateProviderFactory.class)
public class K8sDeploymentStateProviderFactory implements DeploymentStateProviderFactory {

    private static final Logger LOG = Logger.getLogger(K8sDeploymentStateProviderFactory.class);

    // Same id as the built-in JpaDeploymentStateProviderFactory (order 1); the higher order below
    // makes Keycloak select this one instead.
    private static final String PROVIDER_ID = "jpa";

    /**
     * Set by {@link #create} when the server has no {@link JpaConnectionProvider} (no relational
     * database). {@code create} runs during model migration, before Keycloak publishes
     * {@link PostMigrationEvent}, so the listener registered in {@link #postInit} sees the resolved
     * value and knows whether it must publish a seed-derived tag (there is no {@code MIGRATION_MODEL}
     * row for Keycloak to publish one from). Reset per boot in {@link #init} because embedded test
     * runs reuse the JVM across servers started with different options.
     */
    private volatile boolean noRelationalDatabase;

    @Override
    public DeploymentStateProvider create(KeycloakSession session) {
        return create(session.getProvider(JpaConnectionProvider.class));
    }

    /**
     * Split from {@link #create(KeycloakSession)} so the JPA-present and database-free branches are
     * unit-testable without a full session. A {@code null} connection provider means no relational
     * database (e.g. the dynamic areas are served by the Cassandra extension).
     */
    DeploymentStateProvider create(JpaConnectionProvider jpa) {
        if (jpa != null) {
            // Delegate exactly like the built-in provider: a JPA-backed migration model.
            return deploymentState(new MigrationModelAdapter(jpa.getEntityManager()));
        }
        // No relational database: derive the resources tag from the seed and publish it now too
        // (create runs during migration, before the PostMigrationEvent listener fires). Republishing
        // a deterministic value is idempotent and harmless.
        noRelationalDatabase = true;
        String tag = seededResourcesVersion();
        Version.RESOURCES_VERSION = tag;
        return deploymentState(new DbLessMigrationModel(tag));
    }

    @Override
    public void init(Config.Scope config) {
        // Re-read per boot: embedded test runs reuse the JVM across servers with different options.
        noRelationalDatabase = false;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        String configuredSeed = K8sStoreConfig.get().getResourcesVersionSeed();
        factory.register(event -> {
            if (!(event instanceof PostMigrationEvent)) {
                return;
            }
            String seed = configuredSeed;
            if (seed == null || seed.isBlank()) {
                if (!noRelationalDatabase) {
                    // Relational database, no explicit seed: keep Keycloak's random per-database
                    // MIGRATION_MODEL tag, republished from raw SQL by the JPA connection provider.
                    return;
                }
                // No database, no seed: a deterministic tag derived from the Keycloak version.
                seed = "";
            }
            String tag = resourcesVersionFromSeed(seed);
            Version.RESOURCES_VERSION = tag;
            LOG.infof("Pinned theme resources version to %s", tag);
        });
    }

    private static DeploymentStateProvider deploymentState(MigrationModel model) {
        return new DeploymentStateProvider() {
            @Override
            public MigrationModel getMigrationModel() {
                return model;
            }

            @Override
            public void close() {}
        };
    }

    // The seed-derived resources tag: the configured resources-version-seed, or - when unset - the
    // Keycloak version alone. Used for the database-free migration model; a stable, coordination-free
    // value that every replica computes identically and that rotates on a Keycloak upgrade.
    private static String seededResourcesVersion() {
        String seed = K8sStoreConfig.get().getResourcesVersionSeed();
        return resourcesVersionFromSeed(seed == null || seed.isBlank() ? "" : seed);
    }

    // Deterministic 5-char resources tag hashed from the seed and the running Keycloak version.
    // Folding in the version rotates the tag across Keycloak upgrades (whose bundled assets change)
    // while keeping it stable for every replica and redeploy of one version. Must be exactly 5 chars
    // of [0-9a-z] to satisfy Keycloak's ThemeResource RESOURCE_TAG_PATTERN; a hex digest slice fits.
    static String resourcesVersionFromSeed(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + '\0' + Version.VERSION).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {}

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    // Beat the built-in JpaDeploymentStateProviderFactory (order 1) so this provider is selected.
    @Override
    public int order() {
        return 100;
    }

    /**
     * A {@link MigrationModel} for deployments without a relational database. There is no
     * {@code MIGRATION_MODEL} table: the stored version reads as absent (so
     * {@link CrMigrationManager}'s version check is a no-op), the resources tag is the seed-derived
     * value fixed at construction, and persisting a stored version is a no-op. Contrast the
     * Cassandra extension's own database-free model, which throws on these calls - k8store supplies a
     * migration-safe one because {@link CrMigrationManager} always drives migration when realms are
     * CR-backed.
     */
    static final class DbLessMigrationModel implements MigrationModel {

        private final String resourcesTag;

        DbLessMigrationModel(String resourcesTag) {
            this.resourcesTag = resourcesTag;
        }

        @Override
        public String getStoredVersion() {
            return null;
        }

        @Override
        public String getResourcesTag() {
            return resourcesTag;
        }

        @Override
        public void setStoredVersion(String version) {
            // No MIGRATION_MODEL table to persist to; the resources tag is seed-derived instead.
        }
    }
}
