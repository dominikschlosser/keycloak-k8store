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
package io.github.dominikschlosser.k8store.tests.config;

import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * Read-only mode with {@code resolve-references=true}: config CRs are served with their
 * {@code valuesFrom} references resolved from Secrets and ConfigMaps in the namespace. Like
 * {@link ReadOnlyK8StoreServerConfig} it needs a prior write-mode boot in this JVM to have
 * populated the master realm CRs.
 */
public class ReferenceResolutionServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return K8StoreServerConfig.commonOptions(config)
                .option("spi-datastore--k8store--read-only", "true")
                .option("spi-datastore--k8store--resolve-references", "true");
    }
}
