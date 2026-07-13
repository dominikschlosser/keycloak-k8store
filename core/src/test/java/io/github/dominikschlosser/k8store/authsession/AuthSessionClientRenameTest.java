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
package io.github.dominikschlosser.k8store.authsession;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.dominikschlosser.k8store.crd.AuthSessionSpec;
import io.github.dominikschlosser.k8store.crd.AuthTabSpec;
import io.github.dominikschlosser.k8store.crd.RealmSpec;
import io.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import io.github.dominikschlosser.k8store.realm.RealmAdapter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * An auth-session tab records its client by clientId (this store's client id), so a clientId
 * rename must retarget the tab; otherwise the flow's tab lookup (client.getId() vs
 * tab.getClientId()) fails and an in-flight login for the renamed client aborts.
 */
@EnableKubernetesMockClient(crud = true)
class AuthSessionClientRenameTest {

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

    @Test
    void clientRenameRetargetsEmbeddedTabs() {
        start();
        RealmModel realm = realm("master");

        AuthTabSpec tab = new AuthTabSpec();
        tab.setClientId("web");
        AuthSessionSpec root = new AuthSessionSpec();
        root.setId("root-1");
        root.setRealm("master");
        Map<String, AuthTabSpec> tabs = new HashMap<>();
        tabs.put("tab-1", tab);
        root.setTabs(tabs);
        AuthSessionCrStore.save(root);

        new AuthSessionCrProvider(null).onClientRenamed(realm, "web", "portal");

        AuthTabSpec rekeyed =
                AuthSessionCrStore.read("master", "root-1").getTabs().get("tab-1");
        assertEquals("portal", rekeyed.getClientId(), "the tab now points at the renamed client");
    }
}
