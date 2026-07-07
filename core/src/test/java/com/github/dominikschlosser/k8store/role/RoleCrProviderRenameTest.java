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
package com.github.dominikschlosser.k8store.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.client.ClientAdapter;
import com.github.dominikschlosser.k8store.crd.ClientSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.representations.idm.RoleRepresentation.Composites;
import org.keycloak.utils.KeycloakSessionUtil;

@EnableKubernetesMockClient(crud = true)
class RoleCrProviderRenameTest {

    KubernetesClient client;

    @AfterEach
    void shutdownBackend() {
        KeycloakSessionUtil.setKeycloakSession(null);
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();
    }

    private void start() {
        K8sStoreConfig config = K8sStoreConfig.of(false, EnumSet.allOf(Area.class), "test", false, 30);
        K8sStorageBackend.initWithClient(client, config);
    }

    private RealmModel realm(String id) {
        RealmSpec spec = new RealmSpec();
        spec.setRealm(id);
        return new RealmAdapter(null, spec);
    }

    private RoleModel realmRole(RealmModel realm, String name) {
        RoleSpec spec = new RoleSpec();
        spec.setId(name);
        spec.setName(name);
        spec.setRealm(realm.getId());
        spec.setContainerId(realm.getId());
        return new RoleAdapter(null, realm, spec);
    }

    private RoleModel clientRole(RealmModel realm, String clientInternalId, String name) {
        RoleSpec spec = new RoleSpec();
        spec.setId("web:" + name);
        spec.setName(name);
        spec.setRealm(realm.getId());
        spec.setClientRole(true);
        spec.setContainerId(clientInternalId);
        return new RoleAdapter(null, realm, spec);
    }

    private ClientModel client(RealmModel realm, String clientId) {
        ClientSpec spec = new ClientSpec();
        spec.setId(clientId);
        spec.setClientId(clientId);
        spec.setRealm(realm.getId());
        return new ClientAdapter(null, realm, spec, new ConcurrentHashMap<>());
    }

    @Test
    void realmRoleRenameRewritesRealmComposites() {
        start();
        RealmModel realm = realm("master");

        RoleSpec parent = new RoleSpec();
        parent.setId("parent");
        parent.setName("parent");
        parent.setRealm("master");
        parent.setContainerId("master");
        Composites composites = new Composites();
        composites.setRealm(new LinkedHashSet<>(List.of("viewer", "admin", "editor")));
        parent.setComposites(composites);
        RoleCrStore.save(parent);

        new RoleCrProvider(null).roleRenamed(realm, realmRole(realm, "admin"), "administrator");

        Composites read = RoleCrStore.read("master", "parent").getComposites();
        assertTrue(read.getRealm().contains("administrator"), "renamed role is present under the new name");
        assertFalse(read.getRealm().contains("admin"), "the old role name is gone from composites");
    }

    @Test
    void clientRoleRenameRewritesClientCompositesInPlace() {
        start();
        RealmModel realm = realm("master");

        RoleSpec parent = new RoleSpec();
        parent.setId("parent");
        parent.setName("parent");
        parent.setRealm("master");
        parent.setContainerId("master");
        Composites composites = new Composites();
        Map<String, List<String>> byClient = new HashMap<>();
        byClient.put("web-internal", new ArrayList<>(List.of("create", "view", "delete")));
        composites.setClient(byClient);
        parent.setComposites(composites);
        RoleCrStore.save(parent);

        new RoleCrProvider(null).roleRenamed(realm, clientRole(realm, "web-internal", "view"), "read");

        Composites read = RoleCrStore.read("master", "parent").getComposites();
        assertEquals(List.of("create", "read", "delete"), read.getClient().get("web-internal"),
                "client composite keeps position and swaps the renamed role, key unchanged");
    }

    @Test
    void clientRenameMovesClientRoleCrsAndRekeysComposites() {
        start();
        RealmModel realm = realm("master");

        // a client role of the renamed client: its CR id and container id are keyed by the clientId
        RoleSpec clientRoleSpec = new RoleSpec();
        clientRoleSpec.setId("web:view");
        clientRoleSpec.setName("view");
        clientRoleSpec.setRealm("master");
        clientRoleSpec.setClientRole(true);
        clientRoleSpec.setContainerId("web");
        RoleCrStore.save(clientRoleSpec);

        // another role whose composites reference the renamed client's roles by clientId
        RoleSpec parent = new RoleSpec();
        parent.setId("parent");
        parent.setName("parent");
        parent.setRealm("master");
        parent.setContainerId("master");
        Composites composites = new Composites();
        Map<String, List<String>> byClient = new HashMap<>();
        byClient.put("web", new ArrayList<>(List.of("view")));
        composites.setClient(byClient);
        parent.setComposites(composites);
        RoleCrStore.save(parent);

        new RoleCrProvider(null).clientRenamed(realm, client(realm, "web"), "portal");

        assertNull(RoleCrStore.read("master", "web:view"), "the old client-role CR id is gone");
        RoleSpec moved = RoleCrStore.read("master", "portal:view");
        assertNotNull(moved, "the client-role CR moved to the new clientId-keyed id");
        assertEquals("portal", moved.getContainerId(), "the role container id moved to the new clientId");
        assertEquals("view", moved.getName(), "the role name is unchanged by a clientId rename");

        Composites readComposites = RoleCrStore.read("master", "parent").getComposites();
        assertNull(readComposites.getClient().get("web"), "the old clientId composite key is gone");
        assertEquals(List.of("view"), readComposites.getClient().get("portal"),
                "the composite client section is rekeyed to the new clientId");
    }
}
