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
package io.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserCr;
import io.github.dominikschlosser.k8store.tests.config.DynamicAreasServerConfig;
import io.github.dominikschlosser.k8store.tests.federation.TestFederationUserStorage;
import io.github.dominikschlosser.k8store.tests.framework.Await;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespaces;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.storage.UserStorageManager;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.remote.runonserver.InjectRunOnServer;
import org.keycloak.testframework.remote.runonserver.RunOnServerClient;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * User-storage federation compatibility of the CR-backed user area: {@code session.users()} is
 * Keycloak's {@code UserStorageManager} with the CR provider as its local storage, so a
 * registered federation provider (a tiny test-classpath {@link TestFederationUserStorage}, no
 * LDAP server needed) imports its users as {@code KeycloakUser} CRs - federation link, login
 * through the federated credential fan-out, imported-user search, unlink and remove-imported
 * all work against the CR store.
 */
@Order(1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@KeycloakIntegrationTest(config = DynamicAreasServerConfig.class)
public class UserFederationStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace(ref = TestNamespaces.DYNAMIC_REF)
    TestNamespace namespace;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    @InjectRunOnServer
    RunOnServerClient runOnServer;

    private KeycloakUserCr userCr(String username) {
        return userCrs().stream()
                .filter(cr -> username.equals(cr.getSpec().getUsername())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElse(null);
    }

    private List<KeycloakUserCr> userCrs() {
        return kube.client()
                .resources(KeycloakUserCr.class)
                .inNamespace(namespace.name())
                .list()
                .getItems();
    }

    private int passwordGrant(String username, String password) throws Exception {
        String form = "grant_type=password&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String registerFederationProvider(String name) {
        ComponentRepresentation component = new ComponentRepresentation();
        component.setName(name);
        component.setProviderId(TestFederationUserStorage.PROVIDER_ID);
        component.setProviderType(UserStorageProvider.class.getName());
        try (Response response = realm.admin().components().add(component)) {
            assertEquals(
                    201,
                    response.getStatus(),
                    () -> "registering the test federation provider must succeed (is the test classpath "
                            + "provider deployed?): " + response.readEntity(String.class));
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    @Order(1)
    public void usersAreServedThroughTheFederationAwareStorageManager() {
        String report =
                runOnServer.fetchString(session -> session.users().getClass().getName());
        assertTrue(
                report.contains(UserStorageManager.class.getName()),
                "session.users() must be the federation-aware UserStorageManager, got: " + report);
    }

    @Test
    @Order(2)
    public void federatedUserIsImportedAsUserCrAndItsLifecycleWorks() throws Exception {
        String componentId = registerFederationProvider("test-federation");
        try {
            // the first login triggers the import: lookup miss in the CR local storage, the
            // federation provider creates the shadow user (a KeycloakUser CR) and validates
            // the password itself (the CR stores no credentials for this user)
            assertEquals(
                    200,
                    passwordGrant(TestFederationUserStorage.USERNAME, TestFederationUserStorage.PASSWORD),
                    "federated login must succeed");

            KeycloakUserCr imported = userCr(TestFederationUserStorage.USERNAME);
            assertNotNull(imported, "the imported federated user must be a KeycloakUser CR");
            assertEquals(
                    componentId,
                    imported.getSpec().getFederationLink(),
                    "the CR must carry the federation link to the storage provider component");
            assertEquals(TestFederationUserStorage.EMAIL, imported.getSpec().getEmail());
            assertNull(
                    imported.getSpec().getCredentials(),
                    "the shadow user must not store credentials - validation stays federated");

            // wrong password: the federated validator rejects, no local fallback
            assertEquals(400, passwordGrant(TestFederationUserStorage.USERNAME, "wrong-password"));

            // the imported user is visible through the admin API (import validation passes)
            List<UserRepresentation> found = realm.admin().users().search(TestFederationUserStorage.USERNAME, true);
            assertEquals(1, found.size(), "admin search must find the imported user");
            assertEquals(componentId, found.get(0).getFederationLink());

            // unlink keeps the user CR but clears the federation link
            realm.admin().userStorage().unlink(componentId);
            KeycloakUserCr unlinked = userCr(TestFederationUserStorage.USERNAME);
            assertNotNull(unlinked, "unlink must keep the user CR");
            assertNull(unlinked.getSpec().getFederationLink(), "unlink must clear the federation link");

            // the user still exists locally, so the manager serves it from the CR store;
            // re-establish the link server-side for the removal check below
            String realmName = realm.getName();
            runOnServer.run(session -> {
                var realmModel = session.realms().getRealmByName(realmName);
                session.users()
                        .getUserByUsername(realmModel, TestFederationUserStorage.USERNAME)
                        .setFederationLink(componentId);
            });
            assertEquals(
                    componentId,
                    userCr(TestFederationUserStorage.USERNAME).getSpec().getFederationLink());

            // remove-imported-users deletes the linked shadow CRs
            realm.admin().userStorage().removeImportedUsers(componentId);
            Await.await(
                    "imported user CR to be removed with remove-imported-users",
                    () -> userCr(TestFederationUserStorage.USERNAME) == null);
        } finally {
            realm.admin().components().component(componentId).remove();
        }
    }
}
