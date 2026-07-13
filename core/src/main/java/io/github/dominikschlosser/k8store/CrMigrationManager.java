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
package io.github.dominikschlosser.k8store;

import org.jboss.logging.Logger;
import org.keycloak.common.Version;
import org.keycloak.migration.MigrationModel;
import org.keycloak.migration.ModelVersion;
import org.keycloak.models.DeploymentStateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.storage.MigrationManager;

/**
 * Migration manager used while realms are CR-backed. Keycloak's per-realm {@code MigrateTo*} steps
 * assume the JPA model and must not run against custom resources; content-level migration of CRs
 * across Keycloak versions is an out-of-band concern (the version stamp warning at startup and
 * the CRD schema diff tooling are the operator's signals). This is a documented limitation.
 *
 * <p>The realm migrators are skipped, but the server-global bookkeeping that stock
 * {@code DefaultMigrationManager.migrate()} performs is reproduced here, because it seeds the theme
 * <em>resources tag</em> that cache-busts {@code /resources/{tag}/} URLs. When the stored version is
 * missing or older, {@link MigrationModel#setStoredVersion} writes the {@code MIGRATION_MODEL} row,
 * which generates a fresh per-database tag; {@link QuarkusJpaConnectionProviderFactory}-style boot
 * code then publishes {@link MigrationModel#getResourcesTag()} into {@link Version#RESOURCES_VERSION}
 * (the mutable static both the resource-URL builder and the static-resource route read). Without the
 * row that tag is null and Keycloak falls back to {@link Version#VERSION}, which no static route
 * matches - every themed asset (admin console, login pages) then 404s. Re-publishing the freshly read
 * tag here as well makes the writing replica correct in the same boot (no restart).
 *
 * <p>The <em>fixed</em> {@code resources-version-seed} deployment option is handled in
 * {@link K8sDeploymentStateProviderFactory}, not here: {@code migrate()} only runs on migration
 * boots, whereas the seed must be applied on every boot.
 *
 * <p>This works unchanged whether or not there is a relational database. With one the
 * deployment-state provider hands out a JPA-backed model; without one (for example when the dynamic
 * areas are served by the Cassandra extension) it hands out a database-free model whose stored
 * version is always absent - so the version check below is a no-op - and whose resources tag is
 * seed-derived. See {@link K8sDeploymentStateProviderFactory.DbLessMigrationModel}.
 */
public class CrMigrationManager implements MigrationManager {

    private static final Logger LOG = Logger.getLogger(CrMigrationManager.class);

    private final KeycloakSession session;

    public CrMigrationManager(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void migrate() {
        LOG.info("Skipping realm model migrations: CR-backed config entities are managed out-of-band");
        MigrationModel model =
                session.getProvider(DeploymentStateProvider.class).getMigrationModel();
        String stored = model.getStoredVersion();
        if (stored == null || new ModelVersion(stored).lessThan(new ModelVersion(Version.VERSION))) {
            // With a relational database this persists the MIGRATION_MODEL row and (re)generates the
            // theme resources tag; without one it is a no-op (the tag is seed-derived).
            model.setStoredVersion(Version.VERSION);
        }
        // Publish the tag so the writing replica sees it too (no restart needed). Guard against a
        // null tag defensively; the JPA-backed and database-free models both return a non-null one.
        String resourcesTag = model.getResourcesTag();
        if (resourcesTag != null) {
            Version.RESOURCES_VERSION = resourcesTag;
        }
    }

    @Override
    public void migrate(RealmModel realm, RealmRepresentation rep, boolean skipUserDependent) {
        migrate();
    }
}
