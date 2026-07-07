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
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * The config areas plus the opt-in {@code organization} area, with the {@code organizations}
 * feature enabled, write mode: organization definitions and invitations are served from custom
 * resources, organization backing groups land in the group area as {@code KeycloakGroup} CRs,
 * membership stays on the users (database in this config) and the identity-provider linkage in
 * the realm CR. Users and sessions stay in the database — the bootstrap admin remains shared
 * with the other config-mode servers, so this server uses the common test namespace.
 */
public class OrganizationAreasServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        TestKube.ensureAvailable();
        return K8StoreServerConfig.commonOptions(config, true)
                .option("spi-datastore--k8store--read-only", "false")
                .option("spi-datastore--k8store--areas",
                        "realm,client,client-scope,role,group,identity-provider,organization");
    }
}
