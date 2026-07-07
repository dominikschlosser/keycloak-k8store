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
 * The production pattern of k8store: {@code read-only=true} - config entities are served from
 * custom resources and cannot be changed through Keycloak.
 *
 * <p>A read-only Keycloak cannot bootstrap the master realm on an empty store, so test classes
 * using this config must run after a write-mode class has booted in this JVM (see the class
 * ordering in junit-platform.properties): the test namespace in the kind cluster then
 * already holds the master realm CRs, exactly like a pre-provisioned cluster.
 */
public class ReadOnlyK8StoreServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return K8StoreServerConfig.commonOptions(config).option("spi-datastore--k8store--read-only", "true");
    }
}
