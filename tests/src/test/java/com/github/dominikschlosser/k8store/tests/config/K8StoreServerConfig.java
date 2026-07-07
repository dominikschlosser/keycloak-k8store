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

import com.github.dominikschlosser.k8store.tests.TestKube;
import org.keycloak.common.Profile;
import org.keycloak.testframework.infinispan.CacheType;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * Keycloak under test with the k8store datastore in write mode (read-only=false), backed by a
 * real kind cluster (see {@link TestKube}). Write mode is what admin-API-driven tests need; the
 * read-only production pattern is covered by {@link ReadOnlyK8StoreServerConfig}.
 */
public class K8StoreServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        TestKube.ensureAvailable();
        return commonOptions(config).option("spi-datastore--k8store--read-only", "false");
    }

    static KeycloakServerConfigBuilder commonOptions(KeycloakServerConfigBuilder config) {
        return commonOptions(config, false);
    }

    /**
     * The organizations feature is coupled to the {@code organization} area: with groups served
     * from CRs, the built-in JPA organization store cannot work (it references group rows that
     * do not exist), so the k8store boot validation rejects "feature on, area off" — server
     * configs enable the feature together with the area ({@code organizations = true}) and keep
     * it disabled otherwise.
     */
    static KeycloakServerConfigBuilder commonOptions(KeycloakServerConfigBuilder config, boolean organizations) {
        if (!TestKube.isRemote()) {
            config.option("spi-datastore--k8store--context", TestKube.contextName());
        }
        // The authorization feature stays enabled (upstream default): without the authorization
        // area it is served by JPA, with the area by the CR store. Fine-grained admin
        // permissions v2 stays disabled — preview upstream, and it writes policies at runtime
        // (incompatible with the read-only production pattern anyway).
        if (organizations) {
            config.features(Profile.Feature.ORGANIZATION)
                    .featuresDisabled(Profile.Feature.ADMIN_FINE_GRAINED_AUTHZ_V2)
                    // the infinispan organization cache provider (order 10) hardcodes the jpa
                    // organization store as its delegate and depends on the (disabled) realm
                    // cache — like the realm/authorization caches it must be off so the
                    // CR-backed organization provider wins default resolution
                    .spiOption("organization", "infinispan", "enabled", "false");
        } else {
            config.featuresDisabled(
                    Profile.Feature.ADMIN_FINE_GRAINED_AUTHZ_V2,
                    Profile.Feature.ORGANIZATION);
        }
        return config.features(Profile.Feature.STATELESS)
                .cache(CacheType.LOCAL)
                .dependency("com.github.dominikschlosser", "keycloak-k8store")
                .option("spi-datastore--provider", "k8store")
                .option("spi-datastore--k8store--namespace", TestKube.namespace())
                .spiOption("realm", "jpa", "enabled", "false")
                .spiOption("realm-cache", "default", "enabled", "false")
                // like the realm cache: the informer mirror is the cache when the authorization
                // area is CR-backed, and the infinispan authorization cache would not observe
                // out-of-band CR edits
                .spiOption("authorization-cache", "default", "enabled", "false");
    }
}
