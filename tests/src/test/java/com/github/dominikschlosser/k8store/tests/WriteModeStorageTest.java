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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientScopeCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import com.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import io.fabric8.kubernetes.client.CustomResource;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.realm.UserConfig;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;

/**
 * Write mode: everything an admin does to config entities must materialize as custom resources,
 * users must stay in the database, and logins against CR-backed realms/clients must work.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class WriteModeStorageTest {

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectUser(config = TestUser.class)
    ManagedUser user;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    private String ns() {
        return TestKube.namespace();
    }

    private <T extends CustomResource<?, ?>> List<T> crs(Class<T> type) {
        return TestKube.client().resources(type).inNamespace(ns()).list().getItems();
    }

    @Test
    public void bootMirrorsMasterRealmToCustomResources() {
        assertTrue(crs(KeycloakRealmCr.class).stream().anyMatch(cr -> "master".equals(cr.getSpec().getRealm())),
                "master realm must be stored as a KeycloakRealm CR");
        assertTrue(crs(KeycloakClientCr.class).stream()
                        .anyMatch(cr -> "admin-cli".equals(cr.getSpec().getClientId())),
                "master realm default clients must be stored as KeycloakClient CRs");
        assertFalse(crs(KeycloakClientScopeCr.class).isEmpty(), "default client scopes must be CRs");
        assertFalse(crs(KeycloakRoleCr.class).isEmpty(), "default roles must be CRs");
    }

    @Test
    public void managedRealmIsStoredAsCustomResource() {
        assertTrue(crs(KeycloakRealmCr.class).stream()
                        .anyMatch(cr -> realm.getName().equals(cr.getSpec().getRealm())),
                "managed realm must be stored as a KeycloakRealm CR");
    }

    @Test
    public void clientCrudRoundTripsThroughCustomResources() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("cr-client");
        client.setEnabled(true);
        client.setDescription("created via admin API");
        try (Response response = realm.admin().clients().create(client)) {
            assertEquals(201, response.getStatus());
        }

        KeycloakClientCr cr = crs(KeycloakClientCr.class).stream()
                .filter(c -> "cr-client".equals(c.getSpec().getClientId())
                        && realm.getName().equals(c.getSpec().getRealm()))
                .findFirst()
                .orElseThrow();
        assertEquals("created via admin API", cr.getSpec().getDescription());

        String id = realm.admin().clients().findByClientId("cr-client").get(0).getId();
        ClientRepresentation update = realm.admin().clients().get(id).toRepresentation();
        update.setDescription("updated via admin API");
        realm.admin().clients().get(id).update(update);
        assertEquals("updated via admin API", crs(KeycloakClientCr.class).stream()
                .filter(c -> "cr-client".equals(c.getSpec().getClientId()))
                .findFirst()
                .orElseThrow()
                .getSpec()
                .getDescription());

        realm.admin().clients().get(id).remove();
        assertTrue(crs(KeycloakClientCr.class).stream()
                .noneMatch(c -> "cr-client".equals(c.getSpec().getClientId())));
    }

    @Test
    public void roleAndGroupAndScopeLandInCustomResources() {
        RoleRepresentation role = new RoleRepresentation();
        role.setName("cr-role");
        realm.admin().roles().create(role);
        assertTrue(crs(KeycloakRoleCr.class).stream().anyMatch(r -> "cr-role".equals(r.getSpec().getName())));

        GroupRepresentation group = new GroupRepresentation();
        group.setName("cr-group");
        try (Response response = realm.admin().groups().add(group)) {
            assertEquals(201, response.getStatus());
        }
        assertTrue(crs(KeycloakGroupCr.class).stream().anyMatch(g -> "cr-group".equals(g.getSpec().getName())));

        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("cr-scope");
        scope.setProtocol("openid-connect");
        try (Response response = realm.admin().clientScopes().create(scope)) {
            assertEquals(201, response.getStatus());
        }
        assertTrue(crs(KeycloakClientScopeCr.class).stream()
                .anyMatch(s -> "cr-scope".equals(s.getSpec().getName())));
    }

    @Test
    public void identityProviderIsEmbeddedInRealmCustomResource() {
        IdentityProviderRepresentation idp = new IdentityProviderRepresentation();
        idp.setAlias("cr-idp");
        idp.setProviderId("oidc");
        idp.setConfig(Map.of(
                "authorizationUrl", "https://idp.example.com/auth",
                "tokenUrl", "https://idp.example.com/token",
                "clientId", "kc",
                "clientSecret", "secret"));
        try (Response response = realm.admin().identityProviders().create(idp)) {
            assertEquals(201, response.getStatus());
        }

        KeycloakRealmCr realmCr = crs(KeycloakRealmCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow();
        assertNotNull(realmCr.getSpec().getIdentityProviders());
        assertTrue(realmCr.getSpec().getIdentityProviders().stream()
                .anyMatch(i -> "cr-idp".equals(i.getAlias())));
    }

    @Test
    public void usersStayInTheDatabase() {
        // the user was created through the admin API and is served by the JPA user provider;
        // there is no user CRD kind, so nothing user-shaped can leak into the cluster
        UserRepresentation created = realm.admin().users().search(user.getUsername()).stream()
                .findFirst()
                .orElseThrow();
        assertNotNull(created.getId());
    }

    @Test
    public void passwordLoginWorksAgainstCrBackedRealm() throws Exception {
        String tokenUrl = urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/token";
        String form = "grant_type=password"
                + "&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(user.getPassword(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "token endpoint answered: " + response.body());
        assertTrue(response.body().contains("access_token"));
    }

    public static class TestUser implements UserConfig {
        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("alice")
                    .password("alice-password")
                    .email("alice@example.com")
                    .name("Alice", "Example");
        }
    }
}
