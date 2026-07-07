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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import com.github.dominikschlosser.k8store.role.RoleAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * The authorization store's reaction to a role rename or removal: a role policy stores role ids
 * in its {@code roles} config JSON, and this store's role id encodes the name, so a rename must
 * rewrite the id and a removal must drop it - otherwise the policy keeps referencing a role that
 * no longer exists.
 */
@EnableKubernetesMockClient(crud = true)
class AuthzRoleRenameTest {

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

    private RoleModel clientRole(RealmModel realm, String clientId, String name) {
        RoleSpec spec = new RoleSpec();
        spec.setId(clientId + ":" + name);
        spec.setName(name);
        spec.setRealm(realm.getId());
        spec.setClientRole(true);
        spec.setContainerId(clientId);
        return new RoleAdapter(null, realm, spec);
    }

    private void saveRolePolicy(String id, String rolesConfig) {
        AuthzPolicySpec policy = new AuthzPolicySpec();
        policy.setId(id);
        policy.setRealm("master");
        policy.setType("role");
        Map<String, String> config = new HashMap<>();
        config.put("roles", rolesConfig);
        policy.setConfig(config);
        AuthzCrStore.save(policy);
    }

    @Test
    void roleRenameRewritesRoleIdInRolePolicies() {
        start();
        RealmModel realm = realm("master");
        saveRolePolicy("policy-1", "[{\"id\":\"editor\",\"required\":false},{\"id\":\"viewer\",\"required\":true}]");

        new CrStoreFactory(null).roleRenamed(realm, realmRole(realm, "editor"), "author");

        String rewritten = AuthzCrStore.policy("master", "policy-1").getConfig().get("roles");
        assertTrue(rewritten.contains("\"author\""), rewritten);
        assertFalse(rewritten.contains("\"editor\""), rewritten);
        assertTrue(rewritten.contains("\"viewer\""), "the other referenced role is left untouched: " + rewritten);
    }

    @Test
    void clientRoleRenameRewritesQualifiedRoleId() {
        start();
        RealmModel realm = realm("master");
        saveRolePolicy("policy-1", "[{\"id\":\"web:view\",\"required\":false}]");

        new CrStoreFactory(null).roleRenamed(realm, clientRole(realm, "web", "view"), "read");

        String rewritten = AuthzCrStore.policy("master", "policy-1").getConfig().get("roles");
        assertTrue(rewritten.contains("\"web:read\""), rewritten);
        assertFalse(rewritten.contains("\"web:view\""), rewritten);
    }

    @Test
    void roleRemovalDropsRoleIdFromRolePolicies() {
        start();
        RealmModel realm = realm("master");
        saveRolePolicy("policy-1", "[{\"id\":\"editor\",\"required\":false},{\"id\":\"viewer\",\"required\":true}]");

        new CrStoreFactory(null).roleRemoved(realm, realmRole(realm, "editor"));

        String rewritten = AuthzCrStore.policy("master", "policy-1").getConfig().get("roles");
        assertFalse(rewritten.contains("\"editor\""), rewritten);
        assertTrue(rewritten.contains("\"viewer\""), "the surviving role reference stays: " + rewritten);
    }
}
