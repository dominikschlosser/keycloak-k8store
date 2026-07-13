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
 * A fully pre-provisioned, read-only deployment. Every realm including {@code master} is served
 * from custom resources. No admin user is pre-seeded in the database. This exercises Keycloak's
 * boot-time {@code KC_BOOTSTRAP_ADMIN} ({@code ApplianceBootstrap}) against a master realm that
 * already exists as a CR. {@code isNewInstall()} is false, so master is not recreated.
 * {@code isNoMasterUser()} is true, so the bootstrap admin user is created. The creation must reach
 * only the writable user store, never the read-only master CR.
 *
 * <p>Two knobs make this scenario reachable inside a single JVM.
 *
 * <ul>
 *   <li>The default test namespace already holds a complete master realm CR set, written by the
 *       earlier write-mode boot. See the class ordering in {@code junit-platform.properties}. This
 *       matches a GitOps-provisioned cluster.
 *   <li>A dedicated, otherwise-unused in-memory H2 database ({@code db-url-database}) keeps the user
 *       store empty. The shared {@code dev-mem} database keeps the write-mode bootstrap admin alive
 *       across restarts, which is why {@link ReadOnlyK8StoreServerConfig} never exercises the
 *       empty-admin path. Pointing this server at its own database engineers the
 *       {@code isNoMasterUser()} condition.
 * </ul>
 */
public class ReadOnlyEmptyAdminServerConfig implements KeycloakServerConfig {

    /**
     * A distinct H2 in-memory database name. {@code dev-mem} builds its JDBC URL from the template
     * {@code jdbc:h2:mem:%s}. The {@code %s} comes from {@code db-url-database}. Overriding it yields
     * a separate empty database that never saw the write-mode bootstrap admin.
     */
    private static final String EMPTY_ADMIN_DATABASE = "k8store-empty-admin";

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return K8StoreServerConfig.commonOptions(config)
                .option("spi-datastore--k8store--read-only", "true")
                .option("db-url-database", EMPTY_ADMIN_DATABASE);
    }
}
