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
package com.github.dominikschlosser.k8store;

import org.jboss.logging.Logger;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.storage.MigrationManager;

/**
 * No-op migration manager used while realms are CR-backed. Keycloak's {@code MigrateTo*} steps
 * assume the JPA model and must not run against custom resources; content-level migration of CRs
 * across Keycloak versions is an out-of-band concern (the version stamp warning at startup and
 * the CRD schema diff tooling are the operator's signals). This is a documented limitation.
 */
public class CrMigrationManager implements MigrationManager {

    private static final Logger LOG = Logger.getLogger(CrMigrationManager.class);

    @Override
    public void migrate() {
        LOG.info("Skipping model migrations: CR-backed config entities are managed out-of-band");
    }

    @Override
    public void migrate(RealmModel realm, RealmRepresentation rep, boolean skipUserDependent) {
        migrate();
    }
}
