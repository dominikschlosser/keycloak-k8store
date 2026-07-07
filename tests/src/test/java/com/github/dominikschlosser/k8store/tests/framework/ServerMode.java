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
package com.github.dominikschlosser.k8store.tests.framework;

import org.keycloak.testframework.config.Config;
import org.keycloak.testframework.server.KeycloakServer;

/** Which Keycloak server supplier the test framework selected for this run. */
final class ServerMode {

    private ServerMode() {}

    /**
     * True when the framework runs against the Keycloak deployed in the cluster
     * ({@code KC_TEST_SERVER=remote}, see scripts/e2e.sh) instead of booting an embedded
     * server. Reads the same framework configuration that selects the server supplier, so the
     * answer always matches the supplier actually in use.
     */
    static boolean remote() {
        return "remote".equals(Config.getSelectedSupplier(KeycloakServer.class));
    }
}
