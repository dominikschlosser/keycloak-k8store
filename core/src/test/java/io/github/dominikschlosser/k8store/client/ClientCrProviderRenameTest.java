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
package io.github.dominikschlosser.k8store.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import io.github.dominikschlosser.k8store.crd.RealmSpec;
import io.github.dominikschlosser.k8store.crd.RoleSpec;
import io.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import io.github.dominikschlosser.k8store.realm.RealmAdapter;
import io.github.dominikschlosser.k8store.role.RoleAdapter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.utils.KeycloakSessionUtil;

@EnableKubernetesMockClient(crud = true)
class ClientCrProviderRenameTest {

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

    private ClientCrProvider provider() {
        Map<String, Map<String, Integer>> registeredNodesStore = new ConcurrentHashMap<>();
        return new ClientCrProvider(null, registeredNodesStore);
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
    void clientScopeRenameRewritesAssignmentListsInPlace() {
        start();

        ClientSpec withDefault = new ClientSpec();
        withDefault.setId("web");
        withDefault.setClientId("web");
        withDefault.setRealm("master");
        withDefault.setDefaultClientScopes(new ArrayList<>(List.of("profile", "email", "roles")));
        ClientCrStore.save(withDefault);

        ClientSpec withOptional = new ClientSpec();
        withOptional.setId("api");
        withOptional.setClientId("api");
        withOptional.setRealm("master");
        withOptional.setOptionalClientScopes(new ArrayList<>(List.of("address", "email", "phone")));
        ClientCrStore.save(withOptional);

        ClientSpec unrelated = new ClientSpec();
        unrelated.setId("other");
        unrelated.setClientId("other");
        unrelated.setRealm("master");
        unrelated.setDefaultClientScopes(new ArrayList<>(List.of("profile")));
        ClientCrStore.save(unrelated);

        provider().clientScopeRenamed(realm("master"), "email", "mail");

        assertEquals(
                List.of("profile", "mail", "roles"),
                ClientCrStore.read("master", "web").getDefaultClientScopes(),
                "default assignment list keeps position and swaps the renamed scope");
        assertEquals(
                List.of("address", "mail", "phone"),
                ClientCrStore.read("master", "api").getOptionalClientScopes(),
                "optional assignment list keeps position and swaps the renamed scope");
        assertEquals(
                List.of("profile"),
                ClientCrStore.read("master", "other").getDefaultClientScopes(),
                "a client that does not reference the scope is untouched");
    }

    @Test
    void roleRenameRewritesScopeMappingsInPlace() {
        start();
        RealmModel realm = realm("master");

        ClientSpec web = new ClientSpec();
        web.setId("web");
        web.setClientId("web");
        web.setRealm("master");
        web.setRealmScopeMappings(new ArrayList<>(List.of("viewer", "admin", "editor")));
        Map<String, List<String>> byClient = new HashMap<>();
        byClient.put("api-internal", new ArrayList<>(List.of("create", "view", "delete")));
        web.setClientScopeMappings(byClient);
        ClientCrStore.save(web);

        provider().roleRenamed(realm, realmRole(realm, "admin"), "administrator");
        provider().roleRenamed(realm, clientRole(realm, "api-internal", "view"), "read");

        ClientSpec read = ClientCrStore.read("master", "web");
        assertEquals(
                List.of("viewer", "administrator", "editor"),
                read.getRealmScopeMappings(),
                "realm scope mapping keeps position and swaps the renamed role");
        assertEquals(
                List.of("create", "read", "delete"),
                read.getClientScopeMappings().get("api-internal"),
                "client scope mapping keeps position and swaps the renamed role, key unchanged");
    }

    @Test
    void clientRenameRekeysScopeMappingsInOtherClients() {
        start();
        RealmModel realm = realm("master");

        // another client whose scope mappings reference the renamed client's roles by its clientId
        ClientSpec consumer = new ClientSpec();
        consumer.setId("api");
        consumer.setClientId("api");
        consumer.setRealm("master");
        Map<String, List<String>> byClient = new HashMap<>();
        byClient.put("web", new ArrayList<>(List.of("view", "edit")));
        consumer.setClientScopeMappings(byClient);
        ClientCrStore.save(consumer);

        ClientSpec renamedSpec = new ClientSpec();
        renamedSpec.setId("web");
        renamedSpec.setClientId("web");
        renamedSpec.setRealm("master");
        ClientAdapter renamed = new ClientAdapter(null, realm, renamedSpec, new ConcurrentHashMap<>());

        provider().clientRenamed(realm, renamed, "portal");

        ClientSpec read = ClientCrStore.read("master", "api");
        assertTrue(
                read.getClientScopeMappings().containsKey("portal"),
                "the scope-mapping map is rekeyed to the new clientId");
        assertFalse(read.getClientScopeMappings().containsKey("web"), "the old clientId scope-mapping key is gone");
        assertEquals(
                List.of("view", "edit"),
                read.getClientScopeMappings().get("portal"),
                "the mapped role names are preserved under the new key");
    }
}
