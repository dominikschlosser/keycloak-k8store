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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
}
