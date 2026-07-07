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

import com.github.dominikschlosser.k8store.client.ClientAdapter;
import com.github.dominikschlosser.k8store.crd.ClientSpec;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.crd.UserConsentSpec;
import org.keycloak.common.Profile;
import com.github.dominikschlosser.k8store.crd.IssuedVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.crd.UserVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.crd.UserSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import com.github.dominikschlosser.k8store.role.RoleAdapter;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
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

    private ClientModel client(RealmModel realm, String clientId) {
        ClientSpec spec = new ClientSpec();
        spec.setId(clientId);
        spec.setClientId(clientId);
        spec.setRealm(realm.getId());
        return new ClientAdapter(null, realm, spec, new ConcurrentHashMap<>());
    }

    @Test
    void clientRenameRekeysGrantsRewritesConsentAndServiceAccount() {
        start();
        RealmModel realm = realm("master");

        UserConsentSpec consent = new UserConsentSpec();
        consent.setClientId("web");

        UserSpec user = new UserSpec();
        user.setId("carol");
        user.setUsername("carol");
        user.setRealm("master");
        Map<String, List<String>> byClient = new HashMap<>();
        byClient.put("web", new ArrayList<>(List.of("view", "edit")));
        user.setClientRoles(byClient);
        user.setConsents(new ArrayList<>(List.of(consent)));
        user.setServiceAccountClientId("web");
        UserCrStore.save(user);

        new UserCrProvider(null).clientRenamed(realm, client(realm, "web"), "portal");

        UserSpec read = UserCrStore.read("master", "carol");
        assertTrue(read.getClientRoles().containsKey("portal"), "grant map is rekeyed to the new clientId");
        assertFalse(read.getClientRoles().containsKey("web"), "the old clientId grant key is gone");
        assertEquals(List.of("view", "edit"), read.getClientRoles().get("portal"),
                "the grant names are preserved under the new key");
        assertEquals("portal", read.getConsents().get(0).getClientId(),
                "the consent client reference moves to the new clientId");
        assertEquals("portal", read.getServiceAccountClientId(),
                "the service-account link moves to the new clientId");
    }

    @Test
    void roleRenameRewritesUserGrantsInPlace() {
        start();
        RealmModel realm = realm("master");

        UserSpec user = new UserSpec();
        user.setId("bob");
        user.setUsername("bob");
        user.setRealm("master");
        user.setRealmRoles(new ArrayList<>(List.of("viewer", "admin", "editor")));
        Map<String, List<String>> byClient = new HashMap<>();
        byClient.put("web-internal", new ArrayList<>(List.of("create", "view", "delete")));
        user.setClientRoles(byClient);
        UserCrStore.save(user);

        new UserCrProvider(null).roleRenamed(realm, realmRole(realm, "admin"), "administrator");
        new UserCrProvider(null).roleRenamed(realm, clientRole(realm, "web-internal", "view"), "read");

        UserSpec read = UserCrStore.read("master", "bob");
        assertEquals(List.of("viewer", "administrator", "editor"), read.getRealmRoles(),
                "realm grant keeps position and swaps the renamed role");
        assertEquals(List.of("create", "read", "delete"), read.getClientRoles().get("web-internal"),
                "client grant keeps position and swaps the renamed role, key unchanged");
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

    @Test
    void clientScopeRenameRekeysBoundVerifiableCredentials() {
        Profile.reset();
        Profile.init(Profile.ProfileName.DEFAULT, Map.of(Profile.Feature.OID4VC_VCI, true));
        try {
            start();
            UserVerifiableCredentialSpec vc = new UserVerifiableCredentialSpec();
            vc.setId("vc-1");
            vc.setRealm("master");
            vc.setClientScopeId("email");
            VerifiableCredentialCrStore.saveCredential(vc);

            new UserCrProvider(null).clientScopeRenamed(realm("master"), "email", "mail");

            assertEquals("mail", VerifiableCredentialCrStore.readCredential("master", "vc-1").getClientScopeId(),
                    "a renamed scope's bound credential is rekeyed, not orphaned");
        } finally {
            Profile.reset();
        }
    }

    @Test
    void clientRenameRekeysIssuedVerifiableCredentials() {
        Profile.reset();
        Profile.init(Profile.ProfileName.DEFAULT, Map.of(Profile.Feature.OID4VC_VCI, true));
        try {
            start();
            IssuedVerifiableCredentialSpec issued = new IssuedVerifiableCredentialSpec();
            issued.setId("issued-1");
            issued.setRealm("master");
            issued.setClientId("web");
            VerifiableCredentialCrStore.saveIssued(issued);

            RealmModel realm = realm("master");
            new UserCrProvider(null).clientRenamed(realm, client(realm, "web"), "portal");

            assertEquals("portal", VerifiableCredentialCrStore.findIssuedById("issued-1").getClientId(),
                    "a renamed client's issued credentials are rekeyed, not orphaned");
        } finally {
            Profile.reset();
        }
    }
}
