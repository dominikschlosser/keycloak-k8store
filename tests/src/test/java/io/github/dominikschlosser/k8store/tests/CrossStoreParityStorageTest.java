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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientScopeCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import io.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.realm.UserConfig;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * Cross-store integrity: users live in the database while roles, clients and scopes live in
 * custom resources - role mappings, composites and real token issuance must join the two stores
 * exactly like Keycloak's default storage.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class CrossStoreParityStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectUser(config = CrossStoreUser.class)
    ManagedUser user;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    private String createClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setEnabled(true);
        try (Response response = realm.admin().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String createUser(String username) {
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(username);
        rep.setEnabled(true);
        try (Response response = realm.admin().users().create(rep)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private RoleRepresentation createRealmRole(String name) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(name);
        realm.admin().roles().create(role);
        return realm.admin().roles().get(name).toRepresentation();
    }

    private boolean roleCrExists(String name) {
        return kube.client().resources(KeycloakRoleCr.class).inNamespace(namespace.name()).list().getItems().stream()
                .anyMatch(cr -> name.equals(cr.getSpec().getName())
                        && realm.getName().equals(cr.getSpec().getRealm()));
    }

    @Test
    public void jpaUserGetsRealmAndClientRolesFromCrStore() {
        String userId = createUser("cross-roles-user");
        RoleRepresentation realmRole = createRealmRole("cross-realm-role");

        String clientDbId = createClient("cross-role-client");
        RoleRepresentation clientRole = new RoleRepresentation();
        clientRole.setName("cross-client-role");
        realm.admin().clients().get(clientDbId).roles().create(clientRole);
        RoleRepresentation clientRoleRep = realm.admin()
                .clients()
                .get(clientDbId)
                .roles()
                .get("cross-client-role")
                .toRepresentation();

        realm.admin().users().get(userId).roles().realmLevel().add(List.of(realmRole));
        realm.admin().users().get(userId).roles().clientLevel(clientDbId).add(List.of(clientRoleRep));

        assertTrue(
                realm.admin().users().get(userId).roles().realmLevel().listAll().stream()
                        .anyMatch(r -> "cross-realm-role".equals(r.getName())),
                "realm role mapping on the JPA user must read back");
        assertTrue(
                realm.admin().users().get(userId).roles().clientLevel(clientDbId).listAll().stream()
                        .anyMatch(r -> "cross-client-role".equals(r.getName())),
                "client role mapping on the JPA user must read back");

        assertTrue(roleCrExists("cross-realm-role"), "the mapped realm role must be a custom resource");
        assertTrue(roleCrExists("cross-client-role"), "the mapped client role must be a custom resource");
    }

    @Test
    public void effectiveRolesResolveCompositesAcrossStores() {
        String userId = createUser("cross-composite-user");
        RoleRepresentation parent = createRealmRole("cross-composite-parent");
        RoleRepresentation child = createRealmRole("cross-composite-child");
        realm.admin().roles().get("cross-composite-parent").addComposites(List.of(child));

        realm.admin().users().get(userId).roles().realmLevel().add(List.of(parent));

        List<RoleRepresentation> effective =
                realm.admin().users().get(userId).roles().realmLevel().listEffective();
        assertTrue(
                effective.stream().anyMatch(r -> "cross-composite-parent".equals(r.getName())),
                "directly assigned role must be effective");
        assertTrue(
                effective.stream().anyMatch(r -> "cross-composite-child".equals(r.getName())),
                "composite member resolved from the role CR must be effective for the JPA user");
        assertTrue(
                effective.stream().anyMatch(r -> r.getName().startsWith("default-roles-")),
                "the CR-backed default-roles composite must stay effective");
    }

    @Test
    public void passwordGrantTokenContainsClaimFromCrBackedScopeMapper() throws Exception {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("token-claim-scope");
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of("include.in.token.scope", "false", "display.on.consent.screen", "false"));
        String scopeId;
        try (Response response = realm.admin().clientScopes().create(scope)) {
            scopeId = CreatedResponseUtil.getCreatedId(response);
        }

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("k8store-proof-mapper");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        Map<String, String> config = new HashMap<>();
        config.put("claim.name", "k8store-proof");
        config.put("claim.value", "cr-backed");
        config.put("jsonType.label", "String");
        config.put("access.token.claim", "true");
        mapper.setConfig(config);
        try (Response response =
                realm.admin().clientScopes().get(scopeId).getProtocolMappers().createMapper(mapper)) {
            assertEquals(201, response.getStatus());
        }

        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("token-claim-client");
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        String clientDbId;
        try (Response response = realm.admin().clients().create(client)) {
            clientDbId = CreatedResponseUtil.getCreatedId(response);
        }
        realm.admin().clients().get(clientDbId).addDefaultClientScope(scopeId);

        // both the scope assignment and the mapper must be visible in the cluster before login
        KeycloakClientScopeCr scopeCr = kube
                .client()
                .resources(KeycloakClientScopeCr.class)
                .inNamespace(namespace.name())
                .list()
                .getItems()
                .stream()
                .filter(cr -> "token-claim-scope".equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(scopeCr.getSpec().getProtocolMappers().stream()
                .anyMatch(m -> "k8store-proof-mapper".equals(m.getName())));

        String tokenUrl = urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/token";
        String form = "grant_type=password"
                + "&client_id=token-claim-client"
                + "&username=" + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(user.getPassword(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "token endpoint answered: " + response.body());

        ObjectMapper json = new ObjectMapper();
        String accessToken = json.readTree(response.body()).get("access_token").asText();
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        JsonNode claims = json.readTree(payload);
        assertEquals(
                "cr-backed",
                claims.path("k8store-proof").asText(),
                "the CR-backed scope's hardcoded-claim mapper must drive real token issuance");
    }

    public static class CrossStoreUser implements UserConfig {
        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("cross-store-login-user")
                    .password("cross-store-password")
                    .email("cross-store@example.com")
                    .name("Cross", "Store");
        }
    }
}
