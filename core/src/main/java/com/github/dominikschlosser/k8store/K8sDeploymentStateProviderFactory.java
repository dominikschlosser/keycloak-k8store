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
import jakarta.persistence.EntityManager;
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
 * this one is selected, but its {@link MigrationModel} still delegates to the JPA-backed adapter, so
 * the stored-version bookkeeping the JPA store (and {@link CrMigrationManager}) relies on is
 * unchanged. Only the resources tag differs, and only when configured.
 *
 * <p>Keycloak's JPA connection provider publishes {@link Version#RESOURCES_VERSION} (which the
 * resource-URL builder and the static-resource route both read) from the {@code MIGRATION_MODEL} tag
 * via a raw SQL read on every boot - it bypasses this SPI (so overriding {@link #getMigrationModel}
 * does not influence it) and runs after other providers' {@code postInit} (so setting the static
 * there is clobbered). Keycloak's bootstrap fires {@link PostMigrationEvent} after every provider's
 * {@code postInit}, so re-applying the seeded tag from that listener wins regardless of ordering.
 *
 * <p>When {@code resources-version-seed} is set the tag is a hash of {@code (seed, Keycloak version)}:
 * identical on every replica and redeploy of the same version - so a rolling update keeps in-flight
 * asset URLs valid - and still rotating on a Keycloak upgrade whose bundled assets change. Unset, the
 * random per-database tag is left in place. Mirrors keycloak-cassandra-extension's
 * {@code resourcesVersionSeed}.
 */
@AutoService(DeploymentStateProviderFactory.class)
public class K8sDeploymentStateProviderFactory implements DeploymentStateProviderFactory {

    private static final Logger LOG = Logger.getLogger(K8sDeploymentStateProviderFactory.class);

    // Same id as the built-in JpaDeploymentStateProviderFactory (order 1); the higher order below
    // makes Keycloak select this one instead.
    private static final String PROVIDER_ID = "jpa";

    @Override
    public DeploymentStateProvider create(KeycloakSession session) {
        // Delegate exactly like the built-in provider: a JPA-backed migration model.
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        MigrationModel model = new MigrationModelAdapter(em);
        return new DeploymentStateProvider() {
            @Override
            public MigrationModel getMigrationModel() {
                return model;
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        String seed = K8sStoreConfig.get().getResourcesVersionSeed();
        if (seed == null || seed.isBlank()) {
            return;
        }
        String tag = resourcesVersionFromSeed(seed);
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                Version.RESOURCES_VERSION = tag;
                LOG.infof("Pinned theme resources version to a seeded tag: %s", tag);
            }
        });
    }

    // Deterministic 5-char resources tag hashed from the seed and the running Keycloak version.
    // Folding in the version rotates the tag across Keycloak upgrades (whose bundled assets change)
    // while keeping it stable for every replica and redeploy of one version. Must be exactly 5 chars
    // of [0-9a-z] to satisfy Keycloak's ThemeResource RESOURCE_TAG_PATTERN; a hex digest slice fits.
    private static String resourcesVersionFromSeed(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + '\0' + Version.VERSION).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void init(Config.Scope config) {}

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
}
