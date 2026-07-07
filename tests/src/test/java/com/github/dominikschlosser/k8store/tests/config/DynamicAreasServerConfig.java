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

import com.github.dominikschlosser.k8store.tests.framework.TestNamespaces;
import org.keycloak.common.Profile;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * All areas including the experimental dynamic ones ({@code areas=all}): users, user sessions,
 * auth sessions, login failures, single-use objects and revoked tokens are served from custom
 * resources instead of the database. Write mode, because the admin API drives the tests - but
 * note the dynamic kinds would be writable in read-only mode too.
 *
 * <p>Uses its own namespace ({@link TestNamespaces#dynamicName()}, see there for why the
 * config-mode and users-as-CRs servers must not share a master realm). Test classes using this
 * config inject it with {@code @InjectTestNamespace(ref = TestNamespaces.DYNAMIC_REF)}; the
 * injection is load-bearing - it makes the supplier create the namespace before this server
 * boots.
 */
public class DynamicAreasServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return K8StoreServerConfig.commonOptions(config)
                // deploys the test classpath as a provider source: the tiny test-only
                // user-storage federation provider (UserFederationStorageTest) lives there
                .dependencyCurrentProject()
                // experimental upstream features under test: OID4VC verifiable-credential
                // storage of the user area (Oid4vcAreaStorageTest) and consent scope
                // parameters (ConsentParametersStorageTest)
                .features(Profile.Feature.OID4VC_VCI, Profile.Feature.PARAMETERIZED_SCOPES)
                .option("spi-datastore--k8store--namespace", TestNamespaces.dynamicName())
                .option("spi-datastore--k8store--read-only", "false")
                .option("spi-datastore--k8store--areas", "all");
    }
}
