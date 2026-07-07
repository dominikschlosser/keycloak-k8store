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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.tests.config.ReadOnlyK8StoreServerConfig;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;

/**
 * The production pattern: Keycloak serves config entities from pre-existing custom resources
 * (written by the earlier write-mode boot in this JVM — the mock cluster outlives server
 * restarts) and rejects every config write, while dynamic data (users) stays writable.
 */
@Order(2)
@KeycloakIntegrationTest(config = ReadOnlyK8StoreServerConfig.class)
public class ReadOnlyStorageTest {

    @InjectAdminClient(mode = InjectAdminClient.Mode.BOOTSTRAP)
    Keycloak adminClient;

    @Test
    public void masterRealmIsServedFromCustomResources() {
        RealmRepresentation master = adminClient.realm("master").toRepresentation();
        assertNotNull(master);
        assertEquals("master", master.getRealm());
        assertTrue(adminClient.realm("master").clients().findByClientId("admin-cli").size() > 0);
    }

    @Test
    public void configWritesAreRejected() {
        RealmRepresentation master = adminClient.realm("master").toRepresentation();
        master.setDisplayName("should-not-work");
        WebApplicationException updateFailure = assertThrows(WebApplicationException.class,
                () -> adminClient.realm("master").update(master));
        assertTrue(updateFailure.getResponse().getStatus() >= 400);

        RealmRepresentation newRealm = new RealmRepresentation();
        newRealm.setRealm("read-only-reject");
        newRealm.setEnabled(true);
        WebApplicationException createFailure =
                assertThrows(WebApplicationException.class, () -> adminClient.realms().create(newRealm));
        assertTrue(createFailure.getResponse().getStatus() >= 400);

        RoleRepresentation role = new RoleRepresentation();
        role.setName("read-only-role");
        WebApplicationException roleFailure = assertThrows(WebApplicationException.class,
                () -> adminClient.realm("master").roles().create(role));
        assertTrue(roleFailure.getResponse().getStatus() >= 400);
    }

    @Test
    public void outOfBandCrChangesBecomeVisibleWithoutRestart() {
        KeycloakRealmCr masterCr = TestKube.client()
                .resources(KeycloakRealmCr.class)
                .inNamespace(TestKube.namespace())
                .list()
                .getItems()
                .stream()
                .filter(cr -> "master".equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow();

        // out-of-band change, the way a GitOps pipeline would do it
        masterCr.getSpec().setDisplayName("changed-out-of-band");
        TestKube.client().resource(masterCr).update();

        TestKube.await("display name change to propagate through the informer", () ->
                "changed-out-of-band".equals(adminClient.realm("master").toRepresentation().getDisplayName()));
    }

    @Test
    public void clientScopeGroupAndIdpWritesAreRejected() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("read-only-client");
        client.setEnabled(true);
        try (Response response = adminClient.realm("master").clients().create(client)) {
            assertTrue(response.getStatus() >= 400, "client creation must be rejected in read-only mode");
        }

        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("read-only-scope");
        scope.setProtocol("openid-connect");
        try (Response response = adminClient.realm("master").clientScopes().create(scope)) {
            assertTrue(response.getStatus() >= 400, "client scope creation must be rejected in read-only mode");
        }

        GroupRepresentation group = new GroupRepresentation();
        group.setName("read-only-group");
        try (Response response = adminClient.realm("master").groups().add(group)) {
            assertTrue(response.getStatus() >= 400, "group creation must be rejected in read-only mode");
        }

        IdentityProviderRepresentation idp = new IdentityProviderRepresentation();
        idp.setAlias("read-only-idp");
        idp.setProviderId("oidc");
        idp.setConfig(Map.of(
                "authorizationUrl", "https://idp.example.com/auth",
                "tokenUrl", "https://idp.example.com/token",
                "clientId", "kc",
                "clientSecret", "secret"));
        try (Response response = adminClient.realm("master").identityProviders().create(idp)) {
            assertTrue(response.getStatus() >= 400, "identity provider creation must be rejected in read-only mode");
        }
    }

    @Test
    public void existingClientUpdateAndDeleteAreRejected() {
        String adminCliId = adminClient.realm("master").clients().findByClientId("admin-cli").get(0).getId();
        ClientRepresentation update = adminClient.realm("master").clients().get(adminCliId).toRepresentation();
        update.setDescription("read-only-should-fail");

        WebApplicationException updateFailure = assertThrows(WebApplicationException.class,
                () -> adminClient.realm("master").clients().get(adminCliId).update(update));
        assertTrue(updateFailure.getResponse().getStatus() >= 400);

        WebApplicationException deleteFailure = assertThrows(WebApplicationException.class,
                () -> adminClient.realm("master").clients().get(adminCliId).remove());
        assertTrue(deleteFailure.getResponse().getStatus() >= 400);

        assertEquals(1, adminClient.realm("master").clients().findByClientId("admin-cli").size(),
                "the client must survive the rejected delete");
    }

    @Test
    public void outOfBandNewRealmBecomesVisibleWithoutRestart() {
        KeycloakRealmCr masterCr = TestKube.client()
                .resources(KeycloakRealmCr.class)
                .inNamespace(TestKube.namespace())
                .list()
                .getItems()
                .stream()
                .filter(cr -> "master".equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow();
        KeycloakRoleCr masterDefaultRoleCr = TestKube.client()
                .resources(KeycloakRoleCr.class)
                .inNamespace(TestKube.namespace())
                .list()
                .getItems()
                .stream()
                .filter(cr -> masterCr.getSpec().getDefaultRole().getName().equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow();

        // build the new realm the way a GitOps pipeline would: clone the master CR specs as
        // templates (both are plain Keycloak representations) and adjust identity on the JSON
        // level
        ObjectMapper json = Serialization.jsonMapper();
        ObjectNode roleJson = json.valueToTree(masterDefaultRoleCr.getSpec());
        roleJson.put("id", "gitops-default-role");
        roleJson.put("realm", "gitops-realm");
        roleJson.put("name", "default-roles-gitops-realm");
        roleJson.remove("composites");
        roleJson.put("composite", false);
        ObjectNode realmJson = json.valueToTree(masterCr.getSpec());
        realmJson.put("id", "gitops-realm");
        realmJson.put("realm", "gitops-realm");
        realmJson.put("displayName", "Provisioned out of band");
        realmJson.putObject("defaultRole")
                .put("id", "gitops-default-role")
                .put("name", "default-roles-gitops-realm");

        KeycloakRoleCr roleCr = new KeycloakRoleCr();
        KeycloakRealmCr realmCr = new KeycloakRealmCr();
        roleCr.setSpec(json.convertValue(roleJson, RoleSpec.class));
        realmCr.setSpec(json.convertValue(realmJson, RealmSpec.class));
        roleCr.setMetadata(new ObjectMetaBuilder()
                .withName("gitops-realm.default-roles-gitops-realm")
                .withNamespace(TestKube.namespace())
                .build());
        realmCr.setMetadata(new ObjectMetaBuilder()
                .withName("gitops-realm")
                .withNamespace(TestKube.namespace())
                .build());
        TestKube.client().resource(roleCr).create();
        TestKube.client().resource(realmCr).create();

        TestKube.await("out-of-band realm to appear in the realm listing", () ->
                adminClient.realms().findAll().stream()
                        .anyMatch(r -> "gitops-realm".equals(r.getRealm())));
        assertEquals("Provisioned out of band",
                adminClient.realm("gitops-realm").toRepresentation().getDisplayName());
    }

    @Test
    public void usersRemainWritableInReadOnlyMode() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("readonly-mode-user");
        user.setEnabled(true);
        try (Response response = adminClient.realm("master").users().create(user)) {
            assertEquals(201, response.getStatus());
        }
        assertTrue(adminClient.realm("master").users().search("readonly-mode-user").size() > 0);
    }
}
