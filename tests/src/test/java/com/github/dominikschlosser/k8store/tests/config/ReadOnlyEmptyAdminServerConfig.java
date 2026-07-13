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

import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * A fully pre-provisioned, read-only deployment where <em>every</em> realm - including
 * {@code master} - is served from custom resources, and no admin user is pre-seeded in the
 * database. This exercises Keycloak's boot-time {@code KC_BOOTSTRAP_ADMIN}
 * ({@code org.keycloak.services.managers.ApplianceBootstrap}) against a master realm that already
 * exists as a CR: {@code isNewInstall()} is false (master must not be recreated) but
 * {@code isNoMasterUser()} is true, so the bootstrap admin user has to be created into the
 * writable user store without any write reaching the read-only master CR.
 *
 * <p>Two knobs make this scenario reachable inside a single JVM:
 *
 * <ul>
 *   <li>the default test namespace already holds a <em>complete</em> master realm CR set, written
 *       by the earlier write-mode boot (see the class ordering in {@code junit-platform.properties})
 *       - exactly like a GitOps-provisioned cluster;
 *   <li>a dedicated, otherwise-unused in-memory H2 database ({@code db-url-database}) so the user
 *       store starts empty. The shared {@code dev-mem} database keeps the write-mode bootstrap
 *       admin alive across restarts (that is why {@link ReadOnlyK8StoreServerConfig} never
 *       exercises the empty-admin path); pointing this server at its own database is what engineers
 *       the {@code isNoMasterUser()} condition.
 * </ul>
 */
public class ReadOnlyEmptyAdminServerConfig implements KeycloakServerConfig {

    /**
     * A distinct H2 in-memory database name. {@code dev-mem} builds its JDBC URL from the template
     * {@code jdbc:h2:mem:%s} with {@code %s} taken from {@code db-url-database}, so overriding it
     * yields a separate, empty database that never saw the write-mode bootstrap admin.
     */
    private static final String EMPTY_ADMIN_DATABASE = "k8store-empty-admin";

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return K8StoreServerConfig.commonOptions(config)
                .option("spi-datastore--k8store--read-only", "true")
                .option("db-url-database", EMPTY_ADMIN_DATABASE);
    }
}
