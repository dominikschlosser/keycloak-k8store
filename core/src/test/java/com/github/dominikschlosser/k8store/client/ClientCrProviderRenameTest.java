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
package com.github.dominikschlosser.k8store.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.dominikschlosser.k8store.crd.ClientSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
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

        assertEquals(List.of("profile", "mail", "roles"),
                ClientCrStore.read("master", "web").getDefaultClientScopes(),
                "default assignment list keeps position and swaps the renamed scope");
        assertEquals(List.of("address", "mail", "phone"),
                ClientCrStore.read("master", "api").getOptionalClientScopes(),
                "optional assignment list keeps position and swaps the renamed scope");
        assertEquals(List.of("profile"),
                ClientCrStore.read("master", "other").getDefaultClientScopes(),
                "a client that does not reference the scope is untouched");
    }
}
