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
package com.github.dominikschlosser.k8store.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import com.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import com.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzPolicyCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakPermissionTicketCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakResourceServerCr;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.Time;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;
import org.keycloak.storage.ReadOnlyException;

/**
 * Authorization-area behavior of the storage backend: the area is opt-in (not part of the
 * config default but part of {@code all}), requires the client area, gates the registration of
 * its five kinds, splits writability between the config-class kinds and the always-writable
 * permission tickets, and the policy spec round-trips its config/reference sets cleanly.
 */
@EnableKubernetesMockClient(crud = true)
class K8sAuthzKindsTest {

    KubernetesClient client;

    @AfterEach
    void shutdownBackend() {
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();
    }

    private K8sStorageBackend start(boolean readOnly, Set<Area> areas) {
        return K8sStorageBackend.initWithClient(client, K8sStoreConfig.of(readOnly, areas, "test", false, 30));
    }

    // ------------------------------------------------------------------ area grammar

    @Test
    void authorizationAreaIsOptInButJoinsAll() {
        assertFalse(
                K8sStoreConfig.configAreas().contains(Area.AUTHORIZATION),
                "the config default must stay backward-compatible - authorization is opt-in");
        assertFalse(K8sStoreConfig.parseAreas("config").contains(Area.AUTHORIZATION));
        assertTrue(K8sStoreConfig.parseAreas("all").contains(Area.AUTHORIZATION), "the authorization area joins 'all'");
        assertTrue(
                K8sStoreConfig.parseAreas("realm,client,authorization").contains(Area.AUTHORIZATION),
                "explicit lists may name the authorization area");
        assertFalse(
                Area.AUTHORIZATION.isDynamic(), "authorization data is configuration-class (read-only mode applies)");
    }

    @Test
    void authorizationAreaRequiresTheClientArea() {
        assertThrows(
                IllegalArgumentException.class,
                () -> K8sStoreConfig.of(false, EnumSet.of(Area.REALM, Area.AUTHORIZATION), "test", false, 30),
                "resource servers are keyed by their client - the client area is required");
        K8sStoreConfig.reset();
        K8sStoreConfig.of(false, EnumSet.of(Area.REALM, Area.CLIENT, Area.AUTHORIZATION), "test", false, 30);
    }

    // ------------------------------------------------------------------ registration gating

    @Test
    void configOnlyAreasRegisterNoAuthorizationKinds() {
        K8sStorageBackend backend = start(false, K8sStoreConfig.configAreas());
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.read(ResourceServerSpec.class, "master", "my-app"),
                "without the authorization area its kinds must not be registered (no informers, no CRDs needed)");
        assertThrows(
                IllegalArgumentException.class, () -> backend.read(PermissionTicketSpec.class, "master", "some-id"));
    }

    // ------------------------------------------------------------------ writability split

    @Test
    void readOnlyModeRejectsConfigAuthzKindsButKeepsTicketsWritable() {
        start(true, EnumSet.allOf(Area.class));

        ResourceServerSpec server = new ResourceServerSpec();
        server.setClientId("my-app");
        server.setRealm("master");
        assertThrows(
                ReadOnlyException.class,
                () -> K8sStorageBackend.update(ResourceServerSpec.class, "master", "my-app", server),
                "resource servers are configuration: writes must be rejected in read-only mode");

        AuthzPolicySpec policy = new AuthzPolicySpec();
        policy.setId("policy-1");
        policy.setRealm("master");
        policy.setResourceServer("my-app");
        assertThrows(
                ReadOnlyException.class,
                () -> K8sStorageBackend.update(AuthzPolicySpec.class, "master", "policy-1", policy),
                "policies are configuration: writes must be rejected in read-only mode");

        PermissionTicketSpec ticket = new PermissionTicketSpec();
        ticket.setId("ticket-1");
        ticket.setRealm("master");
        ticket.setResourceServer("my-app");
        ticket.setOwner("owner");
        ticket.setRequester("requester");
        ticket.setCreatedTimestamp(Time.currentTimeMillis());
        K8sStorageBackend.update(PermissionTicketSpec.class, "master", "ticket-1", ticket);
        assertEquals(
                1,
                client.resources(KeycloakPermissionTicketCr.class)
                        .inNamespace("test")
                        .list()
                        .getItems()
                        .size(),
                "permission tickets are UMA runtime data: writable despite read-only mode");
        K8sStorageBackend.delete(PermissionTicketSpec.class, "master", "ticket-1");
        assertTrue(client.resources(KeycloakPermissionTicketCr.class)
                .inNamespace("test")
                .list()
                .getItems()
                .isEmpty());
    }

    // ------------------------------------------------------------------ spec round trip

    @Test
    void policySpecRoundTripsReferencesAndDropsNulls() {
        start(false, EnumSet.allOf(Area.class));

        AuthzPolicySpec spec = new AuthzPolicySpec();
        spec.setId("permission-1");
        spec.setRealm("master");
        spec.setResourceServer("my-app");
        spec.setName("resource-permission");
        spec.setType("resource");
        spec.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);
        spec.setLogic(Logic.POSITIVE);
        spec.setResourceIds(Set.of("resource-a"));
        spec.setAssociatedPolicyIds(Set.of("role-policy-1"));
        spec.setConfig(new HashMap<>());
        spec.getConfig().put("some", "value");
        spec.getConfig().put("defaultResourceType", null);

        K8sStorageBackend.update(AuthzPolicySpec.class, "master", "permission-1", spec);

        AuthzPolicySpec read = K8sStorageBackend.get().read(AuthzPolicySpec.class, "master", "permission-1");
        assertNotNull(read);
        assertEquals(DecisionStrategy.AFFIRMATIVE, read.getDecisionStrategy());
        assertEquals(Set.of("resource-a"), read.getResourceIds());
        assertEquals(Set.of("role-policy-1"), read.getAssociatedPolicyIds());
        assertEquals("value", read.getConfig().get("some"));

        // the wire form drops null properties and null config values (422 on a real API server)
        KeycloakAuthzPolicyCr cr = new KeycloakAuthzPolicyCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("permission-1")
                .withNamespace("test")
                .build());
        cr.setSpec(spec);
        String wireJson = K8sStorageBackend.buildSerialization().asJson(cr);
        assertFalse(wireJson.contains(":null"), wireJson);
        assertFalse(wireJson.contains("defaultResourceType"), wireJson);
        assertTrue(wireJson.contains("role-policy-1"), wireJson);
    }

    @Test
    void handAuthoredResourceServerCrDefaultsIdFromMetadataAndRealmFromLabel() {
        KeycloakResourceServerCr cr = new KeycloakResourceServerCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("my-app")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        cr.setSpec(new ResourceServerSpec());
        client.resource(cr).create();

        K8sStorageBackend backend = start(true, EnumSet.allOf(Area.class));

        ResourceServerSpec read = backend.read(ResourceServerSpec.class, "master", "my-app");
        assertNotNull(read, "spec.clientId must default to metadata.name, the realm to the realm label");
        assertEquals("my-app", read.getClientId());
    }
}
