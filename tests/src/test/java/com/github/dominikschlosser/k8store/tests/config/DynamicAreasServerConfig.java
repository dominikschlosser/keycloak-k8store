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
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * All areas including the experimental dynamic ones ({@code areas=all}): users, user sessions,
 * auth sessions, login failures, single-use objects and revoked tokens are served from custom
 * resources instead of the database. Write mode, because the admin API drives the tests — but
 * note the dynamic kinds would be writable in read-only mode too.
 *
 * <p>Uses its own namespace ({@link TestKube#dynamicNamespace()}): with users as CRs, this
 * server's master-realm bootstrap (admin user, temp-admin service account) lives in CRs, while
 * the config-mode servers keep theirs in the shared dev database — the two worlds must not
 * share a master realm.
 */
public class DynamicAreasServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        if (!TestKube.isRemote()) {
            TestKube.dynamicNamespace();
        }
        return K8StoreServerConfig.commonOptions(config)
                // deploys the test classpath as a provider source: the tiny test-only
                // user-storage federation provider (UserFederationStorageTest) lives there
                .dependencyCurrentProject()
                // experimental upstream features under test: OID4VC verifiable-credential
                // storage of the user area (Oid4vcAreaStorageTest) and consent scope
                // parameters (ConsentParametersStorageTest)
                .features(Profile.Feature.OID4VC_VCI, Profile.Feature.PARAMETERIZED_SCOPES)
                .option("spi-datastore--k8store--namespace", TestKube.dynamicNamespace())
                .option("spi-datastore--k8store--read-only", "false")
                .option("spi-datastore--k8store--areas", "all");
    }
}
