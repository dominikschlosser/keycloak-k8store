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
package com.github.dominikschlosser.k8store.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.crd.UserConsentSpec;
import com.github.dominikschlosser.k8store.crd.UserSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.utils.KeycloakSessionUtil;

@EnableKubernetesMockClient(crud = true)
class UserCrProviderRenameTest {

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
    void clientScopeRenameRewritesConsentGrantsAndParameterKeys() {
        start();

        UserConsentSpec consent = new UserConsentSpec();
        consent.setClientId("web");
        consent.setGrantedClientScopes(new ArrayList<>(List.of("profile", "email", "roles")));
        Map<String, List<String>> parameters = new HashMap<>();
        parameters.put("email", new ArrayList<>(List.of("primary")));
        consent.setGrantedScopeParameters(parameters);

        UserSpec user = new UserSpec();
        user.setId("alice");
        user.setUsername("alice");
        user.setRealm("master");
        user.setConsents(new ArrayList<>(List.of(consent)));
        UserCrStore.save(user);

        new UserCrProvider(null).clientScopeRenamed(realm("master"), "email", "mail");

        UserSpec read = UserCrStore.read("master", "alice");
        UserConsentSpec readConsent = read.getConsents().get(0);
        assertEquals(List.of("profile", "mail", "roles"), readConsent.getGrantedClientScopes(),
                "granted-scope list keeps position and swaps the renamed scope");
        assertTrue(readConsent.getGrantedScopeParameters().containsKey("mail"),
                "the scope-parameters map is rekeyed to the new name");
        assertFalse(readConsent.getGrantedScopeParameters().containsKey("email"),
                "the old scope-parameters key is gone");
        assertEquals(List.of("primary"), readConsent.getGrantedScopeParameters().get("mail"),
                "the scope-parameters value is preserved under the new key");
    }
}
