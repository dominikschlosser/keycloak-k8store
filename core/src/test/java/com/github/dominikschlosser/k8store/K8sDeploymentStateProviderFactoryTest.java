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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.Version;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.migration.MigrationModel;
import org.keycloak.models.DeploymentStateProvider;
import org.keycloak.models.KeycloakSession;

/**
 * Unit coverage for the database-free path of {@link K8sDeploymentStateProviderFactory} - the one
 * exercised when k8store is paired with a non-JPA store for the dynamic areas (e.g. the Cassandra
 * extension), so there is no {@link JpaConnectionProvider} and no {@code MIGRATION_MODEL} table.
 */
class K8sDeploymentStateProviderFactoryTest {

    // Keycloak's ThemeResource.RESOURCE_TAG_PATTERN: the resources tag must be exactly 5 lowercase
    // alphanumerics or every /resources/{tag}/... URL 404s.
    private static final String RESOURCE_TAG_PATTERN = "[0-9a-z]{5}";

    private final K8sDeploymentStateProviderFactory factory = new K8sDeploymentStateProviderFactory();

    private String savedResourcesVersion;

    @BeforeEach
    void setUp() {
        savedResourcesVersion = Version.RESOURCES_VERSION;
        // No explicit seed: the database-free path must fall back to a version-derived tag.
        K8sStoreConfig.of(true, EnumSet.of(Area.REALM), "test", false, 30);
        factory.init(null);
    }

    @AfterEach
    void tearDown() {
        Version.RESOURCES_VERSION = savedResourcesVersion;
        K8sStoreConfig.reset();
    }

    @Test
    void seededTagIsFiveLowercaseAlphanumericsDeterministicAndSeedDependent() {
        String a = K8sDeploymentStateProviderFactory.resourcesVersionFromSeed("");
        String b = K8sDeploymentStateProviderFactory.resourcesVersionFromSeed("");
        String c = K8sDeploymentStateProviderFactory.resourcesVersionFromSeed("prod");

        assertTrue(a.matches(RESOURCE_TAG_PATTERN), "tag '" + a + "' must match " + RESOURCE_TAG_PATTERN);
        assertTrue(c.matches(RESOURCE_TAG_PATTERN), "tag '" + c + "' must match " + RESOURCE_TAG_PATTERN);
        assertEquals(a, b, "same seed must yield the same tag on every replica");
        assertNotEquals(a, c, "different seeds must yield different tags");
    }

    @Test
    void withoutJpaServesADatabaseFreeMigrationModelAndPublishesTheTag() {
        DeploymentStateProvider provider = factory.create((JpaConnectionProvider) null);
        MigrationModel model = provider.getMigrationModel();

        // No MIGRATION_MODEL table: stored version reads as absent and the tag is seed-derived.
        assertNull(model.getStoredVersion());
        assertTrue(model.getResourcesTag().matches(RESOURCE_TAG_PATTERN));
        // Persisting a stored version must not throw (unlike the Cassandra extension's own model).
        assertDoesNotThrow(() -> model.setStoredVersion(Version.VERSION));
        // create() publishes the tag immediately (it runs during migration, before PostMigrationEvent).
        assertEquals(model.getResourcesTag(), Version.RESOURCES_VERSION);
    }

    @Test
    void crMigrationManagerPublishesTheTagWithoutAnyRelationalDatabase() {
        // Simulate the Cassandra pairing: no JpaConnectionProvider, k8store's own database-free
        // deployment-state provider selected.
        DeploymentStateProvider provider = factory.create((JpaConnectionProvider) null);
        KeycloakSession session = session(Map.of(DeploymentStateProvider.class, provider));
        Version.RESOURCES_VERSION = "stale";

        assertDoesNotThrow(() -> new CrMigrationManager(session).migrate());

        assertTrue(Version.RESOURCES_VERSION.matches(RESOURCE_TAG_PATTERN));
        assertEquals(provider.getMigrationModel().getResourcesTag(), Version.RESOURCES_VERSION);
    }

    @Test
    void cassandraStyleDeploymentStateIsIncompatibleSoK8storeProviderMustWin() {
        // The Cassandra extension registers its own deployment-state factory under the same 'jpa' id;
        // its database-free MigrationModel throws on getResourcesTag()/setStoredVersion(). If that one
        // were selected instead of k8store's (which has a higher order), CrMigrationManager - always
        // driving migration while realms are CR-backed - would fail fast. This pins that contract.
        KeycloakSession session = session(Map.of(DeploymentStateProvider.class, cassandraStyleDeploymentState()));

        assertThrows(UnsupportedOperationException.class, () -> new CrMigrationManager(session).migrate());
    }

    /** A deployment-state provider shaped like keycloak-cassandra-extension's (throws, no database). */
    private static DeploymentStateProvider cassandraStyleDeploymentState() {
        MigrationModel model = new MigrationModel() {
            @Override
            public String getStoredVersion() {
                return null;
            }

            @Override
            public String getResourcesTag() {
                throw new UnsupportedOperationException("Not supported.");
            }

            @Override
            public void setStoredVersion(String version) {
                throw new UnsupportedOperationException("Not supported.");
            }
        };
        return new DeploymentStateProvider() {
            @Override
            public MigrationModel getMigrationModel() {
                return model;
            }

            @Override
            public void close() {}
        };
    }

    /**
     * A minimal {@link KeycloakSession} that only answers {@code getProvider(Class)} from the given
     * map - enough for {@link CrMigrationManager}, which resolves the {@link DeploymentStateProvider}
     * and nothing else. Avoids pulling a mocking framework into the unit tests.
     */
    private static KeycloakSession session(Map<Class<?>, Object> providers) {
        return (KeycloakSession) Proxy.newProxyInstance(
                K8sDeploymentStateProviderFactoryTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class},
                (proxy, method, args) -> {
                    if ("getProvider".equals(method.getName()) && args != null && args.length == 1) {
                        return providers.get(args[0]);
                    }
                    Class<?> returnType = method.getReturnType();
                    return returnType.isPrimitive() && returnType != void.class ? defaultPrimitive(returnType) : null;
                });
    }

    private static Object defaultPrimitive(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class || type == short.class || type == byte.class || type == char.class) {
            return 0;
        }
        return type == double.class ? 0d : 0f;
    }
}
