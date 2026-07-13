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
package com.github.dominikschlosser.k8store.tests.config;

import com.github.dominikschlosser.k8store.tests.framework.KindClusterSupplier;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespaces;
import org.keycloak.common.Profile;
import org.keycloak.testframework.infinispan.CacheType;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * Keycloak under test with the k8store datastore in write mode (read-only=false), backed by a
 * real kind cluster. The cluster (kubeconfig context, reachability, CRDs) and the test
 * namespace are provided by the test-framework extension suppliers before the server boots;
 * {@link KindClusterSupplier} injects the context option as a server-config interceptor and
 * the namespace name comes from {@link TestNamespaces}. Write mode is what admin-API-driven
 * tests need; the read-only production pattern is covered by {@link ReadOnlyK8StoreServerConfig}.
 */
public class K8StoreServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return commonOptions(config).option("spi-datastore--k8store--read-only", "false");
    }

    static KeycloakServerConfigBuilder commonOptions(KeycloakServerConfigBuilder config) {
        return commonOptions(config, false);
    }

    /**
     * The organizations feature is coupled to the {@code organization} area: with groups served
     * from CRs, the built-in JPA organization store cannot work (it references group rows that
     * do not exist), so the k8store boot validation rejects "feature on, area off" - server
     * configs enable the feature together with the area ({@code organizations = true}) and keep
     * it disabled otherwise.
     */
    static KeycloakServerConfigBuilder commonOptions(KeycloakServerConfigBuilder config, boolean organizations) {
        // The authorization feature stays enabled (upstream default): without the authorization
        // area it is served by JPA, with the area by the CR store. Fine-grained admin
        // permissions v2 stays disabled - preview upstream, and it writes policies at runtime
        // (incompatible with the read-only production pattern anyway).
        if (organizations) {
            config.features(Profile.Feature.ORGANIZATION).featuresDisabled(Profile.Feature.ADMIN_FINE_GRAINED_AUTHZ_V2);
        } else {
            config.featuresDisabled(Profile.Feature.ADMIN_FINE_GRAINED_AUTHZ_V2, Profile.Feature.ORGANIZATION);
        }
        // Selecting the k8store datastore is the opt-in; from there its K8sConfigDefaultsSourceFactory
        // contributes the realm/jpa, realm-cache, authorization-cache and organization/infinispan
        // disables as defaults, so they are not set here. Booting without those flags is exactly what
        // proves the self-configuration is honored end-to-end.
        return config.features(Profile.Feature.STATELESS)
                .cache(CacheType.LOCAL)
                .dependency("com.github.dominikschlosser", "keycloak-k8store")
                .option("spi-datastore--provider", "k8store")
                .option("spi-datastore--k8store--namespace", TestNamespaces.defaultName());
    }
}
