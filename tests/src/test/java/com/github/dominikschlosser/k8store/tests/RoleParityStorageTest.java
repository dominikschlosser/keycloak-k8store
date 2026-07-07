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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import com.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Role parity: composites, attributes, client roles, cascades and search must behave exactly like
 * Keycloak's default storage while every role is materialized as a KeycloakRole custom resource.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class RoleParityStorageTest {

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    private List<KeycloakRoleCr> roleCrs() {
        return TestKube.client().resources(KeycloakRoleCr.class)
                .inNamespace(TestKube.namespace()).list().getItems();
    }

    private KeycloakRoleCr roleCr(String name) {
        return roleCrs().stream()
                .filter(cr -> name.equals(cr.getSpec().getName())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakRole CR named " + name));
    }

    private void createRealmRole(String name) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(name);
        realm.admin().roles().create(role);
    }

    private String createClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setEnabled(true);
        try (Response response = realm.admin().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    public void compositeRealmRoleEmbedsClientRoleInCustomResource() {
        String clientDbId = createClient("role-comp-client");
        RoleRepresentation clientRole = new RoleRepresentation();
        clientRole.setName("role-comp-client-role");
        realm.admin().clients().get(clientDbId).roles().create(clientRole);
        RoleRepresentation clientRoleRep =
                realm.admin().clients().get(clientDbId).roles().get("role-comp-client-role").toRepresentation();

        createRealmRole("role-comp-parent");
        realm.admin().roles().get("role-comp-parent").addComposites(List.of(clientRoleRep));

        assertTrue(realm.admin().roles().get("role-comp-parent").toRepresentation().isComposite(),
                "role must report itself as composite");
        assertTrue(realm.admin().roles().get("role-comp-parent").getClientRoleComposites(clientDbId).stream()
                        .anyMatch(r -> "role-comp-client-role".equals(r.getName())),
                "client-role composite must be readable through the admin API");

        RoleRepresentation.Composites composites = roleCr("role-comp-parent").getSpec().getComposites();
        assertNotNull(composites, "composites must be stored in the CR spec");
        assertNotNull(composites.getClient(), "client-role composites must be stored under composites.client");
        assertTrue(composites.getClient().getOrDefault(clientDbId, List.of()).contains("role-comp-client-role"),
                "CR spec.composites.client must contain the client role name keyed by its client");
    }

    @Test
    public void roleAttributesRoundTripThroughCustomResource() {
        createRealmRole("role-attr");
        RoleRepresentation rep = realm.admin().roles().get("role-attr").toRepresentation();
        rep.setDescription("attributed role");
        rep.setAttributes(Map.of("team", List.of("iam-core"), "cost-center", List.of("42")));
        realm.admin().roles().get("role-attr").update(rep);

        RoleRepresentation readBack = realm.admin().roles().get("role-attr").toRepresentation();
        assertEquals(List.of("iam-core"), readBack.getAttributes().get("team"));
        assertEquals(List.of("42"), readBack.getAttributes().get("cost-center"));

        KeycloakRoleCr cr = roleCr("role-attr");
        assertEquals("attributed role", cr.getSpec().getDescription());
        assertEquals(List.of("iam-core"), cr.getSpec().getAttributes().get("team"));
        assertEquals(List.of("42"), cr.getSpec().getAttributes().get("cost-center"));
    }

    @Test
    public void roleMultiValuedAttributesRoundTripThroughCustomResource() {
        createRealmRole("role-multi-attr");
        RoleRepresentation rep = realm.admin().roles().get("role-multi-attr").toRepresentation();
        rep.setAttributes(Map.of("regions", List.of("eu", "us")));
        realm.admin().roles().get("role-multi-attr").update(rep);

        RoleRepresentation readBack = realm.admin().roles().get("role-multi-attr").toRepresentation();
        assertEquals(List.of("eu", "us"), readBack.getAttributes().get("regions"));
        assertEquals(List.of("eu", "us"), roleCr("role-multi-attr").getSpec().getAttributes().get("regions"));
    }

    @Test
    public void clientRolesAreScopedToTheirClientInCustomResources() {
        String clientDbId = createClient("role-owner-client");
        for (String name : List.of("role-owner-a", "role-owner-b")) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(name);
            realm.admin().clients().get(clientDbId).roles().create(role);
        }

        List<RoleRepresentation> clientRoles = realm.admin().clients().get(clientDbId).roles().list();
        assertTrue(clientRoles.stream().anyMatch(r -> "role-owner-a".equals(r.getName())));
        assertTrue(clientRoles.stream().anyMatch(r -> "role-owner-b".equals(r.getName())));

        assertTrue(realm.admin().roles().list().stream()
                        .noneMatch(r -> "role-owner-a".equals(r.getName())),
                "client roles must not appear among realm roles");

        assertEquals(Boolean.TRUE, roleCr("role-owner-a").getSpec().getClientRole(),
                "CR spec.clientRole must mark the role as client-owned");
        assertEquals(clientDbId, roleCr("role-owner-a").getSpec().getContainerId(),
                "CR spec.containerId must point at the owning client");
        assertEquals(clientDbId, roleCr("role-owner-b").getSpec().getContainerId());
    }

    @Test
    public void deletingRoleRemovesItFromCompositesAndCustomResources() {
        createRealmRole("role-del-parent");
        createRealmRole("role-del-child");
        RoleRepresentation child = realm.admin().roles().get("role-del-child").toRepresentation();
        realm.admin().roles().get("role-del-parent").addComposites(List.of(child));
        assertTrue(roleCr("role-del-parent").getSpec().getComposites().getRealm().contains("role-del-child"));

        realm.admin().roles().deleteRole("role-del-child");

        assertTrue(roleCrs().stream().noneMatch(cr -> "role-del-child".equals(cr.getSpec().getName())),
                "deleted role must disappear from the cluster");
        assertTrue(realm.admin().roles().get("role-del-parent").getRoleComposites().stream()
                        .noneMatch(r -> "role-del-child".equals(r.getName())),
                "deleted role must vanish from composites like in the default storage");
        RoleRepresentation.Composites composites = roleCr("role-del-parent").getSpec().getComposites();
        assertTrue(composites == null || composites.getRealm() == null
                        || !composites.getRealm().contains("role-del-child"),
                "CR spec.composites must not keep a dangling reference to the deleted role");
    }

    @Test
    public void defaultRolesRealmRoleIsCrBackedComposite() {
        String defaultRoleName = realm.admin().toRepresentation().getDefaultRole().getName();
        assertEquals("default-roles-" + realm.getName().toLowerCase(), defaultRoleName);

        assertTrue(realm.admin().roles().get(defaultRoleName).getRoleComposites().stream()
                        .anyMatch(r -> "offline_access".equals(r.getName())),
                "default-roles must be composite over offline_access");

        KeycloakRoleCr cr = roleCr(defaultRoleName);
        assertNotNull(cr.getSpec().getComposites());
        assertNotNull(cr.getSpec().getComposites().getRealm());
        assertTrue(cr.getSpec().getComposites().getRealm().contains("offline_access"),
                "CR spec.composites.realm of default-roles must reference the offline_access role");
    }

    @Test
    public void roleSearchAndPaginationBehaveLikeDefaultStorage() {
        for (int i = 0; i < 5; i++) {
            createRealmRole("role-pag-" + i);
        }

        assertEquals(5, realm.admin().roles().list("role-pag", null, null).size(),
                "search must find all matching roles");
        assertEquals(3, realm.admin().roles().list("role-pag", 0, 3).size(),
                "first page must be limited to max results");
        assertEquals(2, realm.admin().roles().list("role-pag", 3, 10).size(),
                "second page must contain the remainder");
        assertEquals(0, realm.admin().roles().list("role-pag-does-not-exist", null, null).size());

        long crCount = roleCrs().stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && cr.getSpec().getName().startsWith("role-pag-"))
                .count();
        assertEquals(5, crCount, "every created role must be backed by exactly one CR");
    }
}
