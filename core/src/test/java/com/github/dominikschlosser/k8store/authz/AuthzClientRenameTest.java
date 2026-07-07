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
package com.github.dominikschlosser.k8store.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.dominikschlosser.k8store.client.ClientAdapter;
import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import com.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import com.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import com.github.dominikschlosser.k8store.crd.ClientSpec;
import com.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.utils.KeycloakSessionUtil;

@EnableKubernetesMockClient(crud = true)
class AuthzClientRenameTest {

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

    private ClientModel client(RealmModel realm, String clientId) {
        ClientSpec spec = new ClientSpec();
        spec.setId(clientId);
        spec.setClientId(clientId);
        spec.setRealm(realm.getId());
        return new ClientAdapter(null, realm, spec, new ConcurrentHashMap<>());
    }

    @Test
    void clientRenameMovesResourceServerAndRewritesGraphBackReferences() {
        start();
        RealmModel realm = realm("master");

        ResourceServerSpec resourceServer = new ResourceServerSpec();
        resourceServer.setRealm("master");
        resourceServer.setClientId("web");
        AuthzCrStore.save(resourceServer);

        AuthzResourceSpec resource = new AuthzResourceSpec();
        resource.setId("resource-1");
        resource.setRealm("master");
        resource.setResourceServer("web");
        AuthzCrStore.save(resource);

        AuthzScopeSpec scope = new AuthzScopeSpec();
        scope.setId("scope-1");
        scope.setRealm("master");
        scope.setResourceServer("web");
        AuthzCrStore.save(scope);

        AuthzPolicySpec policy = new AuthzPolicySpec();
        policy.setId("policy-1");
        policy.setRealm("master");
        policy.setResourceServer("web");
        AuthzCrStore.save(policy);

        PermissionTicketSpec ticket = new PermissionTicketSpec();
        ticket.setId("ticket-1");
        ticket.setRealm("master");
        ticket.setResourceServer("web");
        AuthzCrStore.save(ticket);

        new CrStoreFactory(null).clientRenamed(realm, client(realm, "web"), "portal");

        assertNull(AuthzCrStore.resourceServer("master", "web"),
                "the resource server keyed by the old clientId is gone");
        assertNotNull(AuthzCrStore.resourceServer("master", "portal"),
                "the resource server moved to the new clientId");
        assertEquals("portal", AuthzCrStore.resource("master", "resource-1").getResourceServer(),
                "the resource back-reference points at the new clientId");
        assertEquals("portal", AuthzCrStore.scope("master", "scope-1").getResourceServer(),
                "the scope back-reference points at the new clientId");
        assertEquals("portal", AuthzCrStore.policy("master", "policy-1").getResourceServer(),
                "the policy back-reference points at the new clientId");
        assertEquals("portal", AuthzCrStore.ticket("master", "ticket-1").getResourceServer(),
                "the permission-ticket back-reference points at the new clientId");
    }

    @Test
    void clientRenameIsANoOpWhenTheClientHasNoResourceServer() {
        start();
        RealmModel realm = realm("master");

        new CrStoreFactory(null).clientRenamed(realm, client(realm, "web"), "portal");

        assertNull(AuthzCrStore.resourceServer("master", "portal"),
                "a client without an authorization graph produces no resource server");
    }
}
