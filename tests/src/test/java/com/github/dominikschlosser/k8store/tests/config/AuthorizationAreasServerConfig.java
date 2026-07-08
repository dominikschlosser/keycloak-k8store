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
 * The config areas plus the opt-in {@code authorization} area, write mode: Authorization
 * Services data (resource servers, resources, authorization scopes, policies, permission
 * tickets) is served from custom resources. Users and sessions stay in the database - the
 * bootstrap admin remains shared with the other config-mode servers, so this server uses the
 * common test namespace.
 */
public class AuthorizationAreasServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return K8StoreServerConfig.commonOptions(config)
                .option("spi-datastore--k8store--read-only", "false")
                .option(
                        "spi-datastore--k8store--areas",
                        "realm,client,client-scope,role,group,identity-provider,authorization");
    }
}
