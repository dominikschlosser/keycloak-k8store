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
 * Partial replacement: {@code group} is left out of the {@code areas} option, so groups are
 * served by Keycloak's default JPA storage while realms, clients, client scopes, roles and
 * identity providers keep coming from custom resources.
 */
public class PartialAreasServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        TestKube.ensureAvailable();
        return K8StoreServerConfig.commonOptions(config)
                .option("spi-datastore--k8store--read-only", "false")
                .option("spi-datastore--k8store--areas", "realm,client,client-scope,role,identity-provider");
    }
}
