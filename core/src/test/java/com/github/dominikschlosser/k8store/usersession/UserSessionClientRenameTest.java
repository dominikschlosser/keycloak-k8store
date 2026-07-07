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
package com.github.dominikschlosser.k8store.usersession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.crd.ClientSessionSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.crd.UserSessionSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * Embedded client sessions are keyed by clientId (this store's client id), so a clientId rename
 * must rekey them; otherwise the renamed client's active sessions orphan and get dropped on the
 * next read (client removal is handled by onClientRemoved, rename by the CLIENT_RENAMED event).
 */
@EnableKubernetesMockClient(crud = true)
class UserSessionClientRenameTest {

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
    void clientRenameRekeysEmbeddedClientSessions() {
        start();
        RealmModel realm = realm("master");

        ClientSessionSpec clientSession = new ClientSessionSpec();
        clientSession.setId("cs-1");
        clientSession.setClientId("web");
        UserSessionSpec session = new UserSessionSpec();
        session.setId("session-1");
        session.setRealm("master");
        Map<String, ClientSessionSpec> clientSessions = new HashMap<>();
        clientSessions.put("web", clientSession);
        session.setClientSessions(clientSessions);
        UserSessionCrStore.save(session);

        new UserSessionCrProvider(null).onClientRenamed(realm, "web", "portal");

        Map<String, ClientSessionSpec> rekeyed = UserSessionCrStore.read("master", "session-1").getClientSessions();
        assertTrue(rekeyed.containsKey("portal"), "the client session is rekeyed to the new clientId");
        assertFalse(rekeyed.containsKey("web"), "the old clientId key is gone");
        assertEquals("portal", rekeyed.get("portal").getClientId(),
                "the embedded client-session clientId is updated too");
    }
}
