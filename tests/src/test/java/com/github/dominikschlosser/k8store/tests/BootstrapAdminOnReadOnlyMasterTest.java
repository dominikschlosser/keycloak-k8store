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
package com.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import com.github.dominikschlosser.k8store.tests.config.ReadOnlyEmptyAdminServerConfig;
import com.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import com.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import com.github.dominikschlosser.k8store.tests.framework.KindCluster;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.config.Config;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * A read-only k8store where the master realm is served entirely from pre-provisioned custom
 * resources and the database holds no admin user. The scenario proves that Keycloak's boot-time
 * {@code KC_BOOTSTRAP_ADMIN} ({@code ApplianceBootstrap}) still creates the admin user even though
 * the master realm already exists as a CR ({@code isNewInstall() == false} but
 * {@code isNoMasterUser() == true}), and that the creation touches only the writable user store -
 * never the read-only master CR (which would raise a {@code ReadOnlyException} and abort boot).
 *
 * <p>Runs after the write-mode boot so the default namespace already holds a complete master CR
 * set; the {@link ReadOnlyEmptyAdminServerConfig} points this server at its own empty in-memory
 * database so the bootstrap-admin path actually fires (see that config for the mechanics).
 */
@Order(3)
@KeycloakIntegrationTest(config = ReadOnlyEmptyAdminServerConfig.class)
public class BootstrapAdminOnReadOnlyMasterTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    private KeycloakRealmCr masterRealmCr() {
        return kube.client().resources(KeycloakRealmCr.class).inNamespace(namespace.name()).list().getItems().stream()
                .filter(cr -> "master".equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The bootstrap admin client of the framework ({@code @InjectAdminClient(BOOTSTRAP)}) logs in
     * through the {@code temp-admin} service account, whose service-account user lives in the
     * database - which this server's fresh database does not have. We deliberately authenticate as
     * the {@code KC_BOOTSTRAP_ADMIN} user (created by {@code ApplianceBootstrap}) instead, since
     * that user is exactly what this test is about.
     */
    private Keycloak bootstrapAdminUserClient() {
        return KeycloakBuilder.builder()
                .serverUrl(urls.getBase())
                .realm("master")
                .grantType(OAuth2Constants.PASSWORD)
                .clientId("admin-cli")
                .username(Config.getAdminUsername())
                .password(Config.getAdminPassword())
                .build();
    }

    @Test
    public void bootstrapAdminIsCreatedAndCanAuthenticate() {
        // Reaching this point already proves the server booted under read-only without a
        // ReadOnlyException: creating the admin user against a pre-existing master CR wrote only
        // dynamic (user-store) data. A successful admin REST call additionally proves the admin
        // role grant landed, i.e. the user really has master-realm admin rights.
        try (Keycloak admin = bootstrapAdminUserClient()) {
            RealmRepresentation master = admin.realm("master").toRepresentation();
            assertNotNull(master, "the bootstrap admin must be able to read the master realm");
            assertEquals("master", master.getRealm());

            List<UserRepresentation> found = admin.realm("master").users().search(Config.getAdminUsername());
            assertTrue(
                    found.stream().anyMatch(u -> Config.getAdminUsername().equals(u.getUsername())),
                    "the bootstrap admin user must exist in the (writable) user store");
        }
    }

    @Test
    public void masterRealmIsServedFromCustomResource() {
        // master exists as a CR (so ApplianceBootstrap saw isNewInstall() == false and did not
        // recreate it), and the CR store is authoritative and read-only.
        assertEquals("master", masterRealmCr().getSpec().getRealm());

        try (Keycloak admin = bootstrapAdminUserClient()) {
            assertTrue(
                    admin.realm("master").clients().findByClientId("admin-cli").size() > 0,
                    "the master realm and its default clients must be served from the CRs");
        }
    }

    @Test
    public void configWritesRemainRejectedWhileUsersStayWritable() {
        try (Keycloak admin = bootstrapAdminUserClient()) {
            // read-only is genuinely in force - a config write must be rejected...
            RealmRepresentation newRealm = new RealmRepresentation();
            newRealm.setRealm("read-only-reject");
            newRealm.setEnabled(true);
            WebApplicationException createFailure = assertThrows(
                    WebApplicationException.class, () -> admin.realms().create(newRealm));
            assertTrue(createFailure.getResponse().getStatus() >= 400);

            // ...while the user store the bootstrap admin was written into stays writable.
            UserRepresentation user = new UserRepresentation();
            user.setUsername("bootstrap-test-user");
            user.setEnabled(true);
            try (Response response = admin.realm("master").users().create(user)) {
                assertEquals(201, response.getStatus(), "users must remain writable in read-only mode");
            }
        }
    }
}
