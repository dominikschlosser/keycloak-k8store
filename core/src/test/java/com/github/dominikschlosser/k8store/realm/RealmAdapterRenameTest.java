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
package com.github.dominikschlosser.k8store.realm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.utils.KeycloakSessionUtil;

@EnableKubernetesMockClient(crud = true)
class RealmAdapterRenameTest {

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

    @Test
    void renameDefaultClientScopeRewritesBothDefaultListsInPlace() {
        start();

        RealmSpec spec = new RealmSpec();
        spec.setRealm("master");
        spec.setDefaultDefaultClientScopes(new ArrayList<>(List.of("profile", "email", "roles")));
        spec.setDefaultOptionalClientScopes(new ArrayList<>(List.of("address", "email", "phone")));
        RealmCrStore.save(spec);

        RealmAdapter realm = new RealmAdapter(null, spec);
        realm.renameDefaultClientScope("email", "mail");

        RealmSpec read = RealmCrStore.read("master");
        assertEquals(List.of("profile", "mail", "roles"), read.getDefaultDefaultClientScopes(),
                "the realm default-scope list keeps position and swaps the renamed scope");
        assertEquals(List.of("address", "mail", "phone"), read.getDefaultOptionalClientScopes(),
                "the realm optional-scope list keeps position and swaps the renamed scope");
    }

    @Test
    void renameDefaultRoleRewritesTheReferenceWhenItMatches() {
        start();

        RealmSpec spec = new RealmSpec();
        spec.setRealm("master");
        RoleRepresentation defaultRole = new RoleRepresentation();
        defaultRole.setId("default-roles-master");
        defaultRole.setName("default-roles-master");
        spec.setDefaultRole(defaultRole);
        RealmCrStore.save(spec);

        RealmAdapter realm = new RealmAdapter(null, spec);
        realm.renameDefaultRole("default-roles-master", "default-roles-primary");

        RealmSpec read = RealmCrStore.read("master");
        assertEquals("default-roles-primary", read.getDefaultRole().getName(),
                "the default-role reference name moves to the new name");
        assertEquals("default-roles-primary", read.getDefaultRole().getId(),
                "the default-role reference id (= name for realm roles) moves too");
    }

    @Test
    void renameDefaultRoleIgnoresANonMatchingReference() {
        start();

        RealmSpec spec = new RealmSpec();
        spec.setRealm("master");
        RoleRepresentation defaultRole = new RoleRepresentation();
        defaultRole.setId("default-roles-master");
        defaultRole.setName("default-roles-master");
        spec.setDefaultRole(defaultRole);
        RealmCrStore.save(spec);

        RealmAdapter realm = new RealmAdapter(null, spec);
        realm.renameDefaultRole("some-other-role", "renamed");

        assertEquals("default-roles-master", RealmCrStore.read("master").getDefaultRole().getName(),
                "a rename of an unrelated role leaves the default-role reference untouched");
    }
}
