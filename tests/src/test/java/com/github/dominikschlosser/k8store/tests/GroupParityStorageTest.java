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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
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
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Group parity: hierarchies, attributes, search and role grants live in KeycloakGroup custom
 * resources, while user membership stays in the database - the cross-store join must behave
 * exactly like Keycloak's default storage.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class GroupParityStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    private List<KeycloakGroupCr> groupCrs() {
        return kube.client().resources(KeycloakGroupCr.class)
                .inNamespace(namespace.name()).list().getItems();
    }

    private KeycloakGroupCr groupCr(String name) {
        return groupCrs().stream()
                .filter(cr -> name.equals(cr.getSpec().getName())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakGroup CR named " + name));
    }

    private String createGroup(String name) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        try (Response response = realm.admin().groups().add(group)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = realm.admin().users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    public void groupHierarchyRoundTripsThroughParentIdInCustomResource() {
        String parentId = createGroup("hier-parent");
        GroupRepresentation child = new GroupRepresentation();
        child.setName("hier-child");
        String childId;
        try (Response response = realm.admin().groups().group(parentId).subGroup(child)) {
            childId = CreatedResponseUtil.getCreatedId(response);
        }

        List<GroupRepresentation> subGroups = realm.admin().groups().group(parentId).getSubGroups(0, 10, true);
        assertEquals(1, subGroups.size());
        assertEquals("hier-child", subGroups.get(0).getName());
        assertEquals("/hier-parent/hier-child", subGroups.get(0).getPath());

        assertEquals(parentId, groupCr("hier-child").getSpec().getParentId(),
                "child CR must reference the parent group id");
        assertNull(groupCr("hier-parent").getSpec().getParentId(), "top-level group CR must have no parent");
        assertEquals(childId, groupCr("hier-child").getSpec().getId());
    }

    @Test
    public void jpaUserJoinsCrBackedGroupAndMembershipReadsBackOnBothEnds() {
        String groupId = createGroup("member-group");
        String userId = createUser("grp-member-user");

        realm.admin().users().get(userId).joinGroup(groupId);

        assertTrue(realm.admin().users().get(userId).groups().stream()
                        .anyMatch(g -> "member-group".equals(g.getName())),
                "user (JPA) must see the CR-backed group membership");
        assertTrue(realm.admin().groups().group(groupId).members().stream()
                        .anyMatch(u -> "grp-member-user".equals(u.getUsername())),
                "group members listing must join back to the JPA user");

        assertNotNull(groupCr("member-group"), "the group itself must be a custom resource");
    }

    @Test
    public void groupAttributesRoundTripThroughCustomResource() {
        String groupId = createGroup("attr-group");
        GroupRepresentation rep = realm.admin().groups().group(groupId).toRepresentation();
        rep.setAttributes(Map.of("tier", List.of("gold"), "region", List.of("eu")));
        realm.admin().groups().group(groupId).update(rep);

        GroupRepresentation readBack = realm.admin().groups().group(groupId).toRepresentation();
        assertEquals(List.of("gold"), readBack.getAttributes().get("tier"));
        assertEquals(List.of("eu"), readBack.getAttributes().get("region"));

        KeycloakGroupCr cr = groupCr("attr-group");
        assertEquals(List.of("gold"), cr.getSpec().getAttributes().get("tier"));
        assertEquals(List.of("eu"), cr.getSpec().getAttributes().get("region"));
    }

    @Test
    public void groupMultiValuedAttributesRoundTripThroughCustomResource() {
        String groupId = createGroup("multi-attr-group");
        GroupRepresentation rep = realm.admin().groups().group(groupId).toRepresentation();
        rep.setAttributes(Map.of("regions", List.of("eu", "us")));
        realm.admin().groups().group(groupId).update(rep);

        GroupRepresentation readBack = realm.admin().groups().group(groupId).toRepresentation();
        assertEquals(List.of("eu", "us"), readBack.getAttributes().get("regions"));
        assertEquals(List.of("eu", "us"), groupCr("multi-attr-group").getSpec().getAttributes().get("regions"));
    }

    @Test
    public void groupSearchFindsCrBackedGroups() {
        createGroup("search-grp-alpha");
        createGroup("search-grp-beta");

        List<GroupRepresentation> hits = realm.admin().groups().groups("search-grp", 0, 10);
        assertTrue(hits.stream().anyMatch(g -> "search-grp-alpha".equals(g.getName())));
        assertTrue(hits.stream().anyMatch(g -> "search-grp-beta".equals(g.getName())));

        List<GroupRepresentation> exact = realm.admin().groups().groups("search-grp-alpha", 0, 10);
        assertTrue(exact.stream().anyMatch(g -> "search-grp-alpha".equals(g.getName())));
        assertTrue(exact.stream().noneMatch(g -> "search-grp-beta".equals(g.getName())),
                "narrower search must not return the sibling group");

        assertEquals(0, realm.admin().groups().groups("search-grp-nothing", 0, 10).size());
    }

    @Test
    public void deletingCrBackedGroupCascadesJpaMembership() {
        String groupId = createGroup("cascade-group");
        String userId = createUser("grp-cascade-user");
        realm.admin().users().get(userId).joinGroup(groupId);
        assertEquals(1, realm.admin().users().get(userId).groups().size());

        realm.admin().groups().group(groupId).remove();

        assertTrue(groupCrs().stream().noneMatch(cr -> "cascade-group".equals(cr.getSpec().getName())),
                "deleted group must disappear from the cluster");
        assertEquals(0, realm.admin().users().get(userId).groups().size(),
                "JPA-side membership must be cascaded away when the CR-backed group is deleted");
    }

    @Test
    public void groupRealmRoleMappingLandsInGrantedRolesOfCustomResource() {
        String groupId = createGroup("role-grant-group");
        RoleRepresentation role = new RoleRepresentation();
        role.setName("group-granted-role");
        realm.admin().roles().create(role);
        RoleRepresentation roleRep = realm.admin().roles().get("group-granted-role").toRepresentation();

        realm.admin().groups().group(groupId).roles().realmLevel().add(List.of(roleRep));

        assertTrue(realm.admin().groups().group(groupId).roles().realmLevel().listAll().stream()
                        .anyMatch(r -> "group-granted-role".equals(r.getName())),
                "role mapping must be readable through the admin API");
        List<String> granted = groupCr("role-grant-group").getSpec().getRealmRoles();
        assertNotNull(granted, "granted realm roles must be stored in the group CR");
        assertTrue(granted.contains("group-granted-role"), "CR spec.realmRoles must contain the role name");

        realm.admin().groups().group(groupId).roles().realmLevel().remove(List.of(roleRep));
        List<String> afterRemove = groupCr("role-grant-group").getSpec().getRealmRoles();
        assertTrue(afterRemove == null || !afterRemove.contains("group-granted-role"),
                "removed role mapping must leave the group CR");
    }
}
