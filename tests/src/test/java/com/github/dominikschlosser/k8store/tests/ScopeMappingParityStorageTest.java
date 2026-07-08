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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientScopeCr;
import com.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import com.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import com.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import com.github.dominikschlosser.k8store.tests.framework.KindCluster;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Scope mapping parity: realm-role and client-role scope mappings on clients and client scopes
 * must round-trip through the admin API and land in the CR specs' scopeMappings.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class ScopeMappingParityStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    private KeycloakClientCr clientCr(String clientId) {
        return kube.client().resources(KeycloakClientCr.class).inNamespace(namespace.name()).list().getItems().stream()
                .filter(cr -> clientId.equals(cr.getSpec().getClientId())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakClient CR for clientId " + clientId));
    }

    private KeycloakClientScopeCr scopeCr(String name) {
        return kube
                .client()
                .resources(KeycloakClientScopeCr.class)
                .inNamespace(namespace.name())
                .list()
                .getItems()
                .stream()
                .filter(cr -> name.equals(cr.getSpec().getName())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakClientScope CR named " + name));
    }

    private String createClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setEnabled(true);
        client.setFullScopeAllowed(false);
        try (Response response = realm.admin().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private RoleRepresentation createRealmRole(String name) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(name);
        realm.admin().roles().create(role);
        return realm.admin().roles().get(name).toRepresentation();
    }

    @Test
    public void realmRoleScopeMappingOnClientLandsInCustomResource() {
        String clientDbId = createClient("scope-map-client");
        RoleRepresentation role = createRealmRole("scope-mapped-role");

        realm.admin().clients().get(clientDbId).getScopeMappings().realmLevel().add(List.of(role));

        assertTrue(
                realm.admin().clients().get(clientDbId).getScopeMappings().realmLevel().listAll().stream()
                        .anyMatch(r -> "scope-mapped-role".equals(r.getName())),
                "realm-level scope mapping must be readable through the admin API");
        List<String> crMappings = clientCr("scope-map-client").getSpec().getRealmScopeMappings();
        assertNotNull(crMappings, "scope mappings must be stored in the client CR");
        assertTrue(crMappings.contains(role.getName()), "client CR realmScopeMappings must contain the role name");

        realm.admin().clients().get(clientDbId).getScopeMappings().realmLevel().remove(List.of(role));
        assertTrue(
                realm.admin()
                        .clients()
                        .get(clientDbId)
                        .getScopeMappings()
                        .realmLevel()
                        .listAll()
                        .isEmpty(),
                "removed scope mapping must disappear from the admin API");
        List<String> afterRemove = clientCr("scope-map-client").getSpec().getRealmScopeMappings();
        assertTrue(
                afterRemove == null || !afterRemove.contains(role.getName()),
                "removed scope mapping must leave the client CR");
    }

    @Test
    public void clientRoleScopeMappingOnClientLandsInCustomResource() {
        String roleOwnerDbId = createClient("scope-map-role-owner");
        RoleRepresentation clientRole = new RoleRepresentation();
        clientRole.setName("scope-mapped-client-role");
        realm.admin().clients().get(roleOwnerDbId).roles().create(clientRole);
        RoleRepresentation clientRoleRep = realm.admin()
                .clients()
                .get(roleOwnerDbId)
                .roles()
                .get("scope-mapped-client-role")
                .toRepresentation();

        String consumerDbId = createClient("scope-map-consumer");
        realm.admin()
                .clients()
                .get(consumerDbId)
                .getScopeMappings()
                .clientLevel(roleOwnerDbId)
                .add(List.of(clientRoleRep));

        assertTrue(
                realm
                        .admin()
                        .clients()
                        .get(consumerDbId)
                        .getScopeMappings()
                        .clientLevel(roleOwnerDbId)
                        .listAll()
                        .stream()
                        .anyMatch(r -> "scope-mapped-client-role".equals(r.getName())),
                "client-level scope mapping must be readable through the admin API");
        Map<String, List<String>> crMappings =
                clientCr("scope-map-consumer").getSpec().getClientScopeMappings();
        assertNotNull(crMappings, "scope mappings must be stored in the consumer client CR");
        assertTrue(
                crMappings.getOrDefault("scope-map-role-owner", List.of()).contains("scope-mapped-client-role"),
                "consumer client CR clientScopeMappings must map the owning clientId to the role name");
    }

    @Test
    public void realmRoleScopeMappingOnClientScopeLandsInCustomResource() {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("scope-map-scope");
        scope.setProtocol("openid-connect");
        String scopeId;
        try (Response response = realm.admin().clientScopes().create(scope)) {
            scopeId = CreatedResponseUtil.getCreatedId(response);
        }
        RoleRepresentation role = createRealmRole("scope-on-scope-role");

        realm.admin()
                .clientScopes()
                .get(scopeId)
                .getScopeMappings()
                .realmLevel()
                .add(List.of(role));

        assertTrue(
                realm.admin().clientScopes().get(scopeId).getScopeMappings().realmLevel().listAll().stream()
                        .anyMatch(r -> "scope-on-scope-role".equals(r.getName())),
                "scope mapping on a client scope must be readable through the admin API");
        List<String> crMappings = scopeCr("scope-map-scope").getSpec().getRealmScopeMappings();
        assertNotNull(crMappings, "scope mappings must be stored in the client scope CR");
        assertTrue(
                crMappings.contains(role.getName()), "client scope CR realmScopeMappings must contain the role name");
    }
}
