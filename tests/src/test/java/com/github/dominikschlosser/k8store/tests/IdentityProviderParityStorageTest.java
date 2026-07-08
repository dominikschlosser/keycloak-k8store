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
package com.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import com.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import com.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import com.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import com.github.dominikschlosser.k8store.tests.framework.KindCluster;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Identity provider parity: IdP CRUD and IdP mappers must behave like the default storage, with
 * providers and mappers embedded in the KeycloakRealm custom resource spec.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class IdentityProviderParityStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    private KeycloakRealmCr realmCr() {
        return kube.client().resources(KeycloakRealmCr.class).inNamespace(namespace.name()).list().getItems().stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakRealm CR for " + realm.getName()));
    }

    private IdentityProviderRepresentation idpRep(String alias) {
        return realmCr().getSpec().getIdentityProviders().stream()
                .filter(i -> alias.equals(i.getAlias()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("identity provider " + alias + " missing from realm CR"));
    }

    private void createIdp(String alias) {
        IdentityProviderRepresentation idp = new IdentityProviderRepresentation();
        idp.setAlias(alias);
        idp.setProviderId("oidc");
        idp.setEnabled(true);
        Map<String, String> config = new HashMap<>();
        config.put("authorizationUrl", "https://idp.example.com/auth");
        config.put("tokenUrl", "https://idp.example.com/token");
        config.put("clientId", "kc-broker");
        config.put("clientSecret", "broker-secret");
        idp.setConfig(config);
        try (Response response = realm.admin().identityProviders().create(idp)) {
            assertEquals(201, response.getStatus());
        }
    }

    @Test
    public void idpMapperIsEmbeddedInRealmCustomResource() {
        createIdp("mapper-idp");

        IdentityProviderMapperRepresentation mapper = new IdentityProviderMapperRepresentation();
        mapper.setName("username-template");
        mapper.setIdentityProviderAlias("mapper-idp");
        mapper.setIdentityProviderMapper("oidc-username-idp-mapper");
        mapper.setConfig(Map.of("syncMode", "INHERIT", "template", "${CLAIM.preferred_username}"));
        try (Response response =
                realm.admin().identityProviders().get("mapper-idp").addMapper(mapper)) {
            assertEquals(201, response.getStatus());
        }

        assertTrue(
                realm.admin().identityProviders().get("mapper-idp").getMappers().stream()
                        .anyMatch(m -> "username-template".equals(m.getName())),
                "mapper must be readable through the admin API");

        assertTrue(
                realmCr().getSpec().getIdentityProviderMappers().stream()
                        .anyMatch(m -> "username-template".equals(m.getName())
                                && "mapper-idp".equals(m.getIdentityProviderAlias())
                                && "oidc-username-idp-mapper".equals(m.getIdentityProviderMapper())
                                && "${CLAIM.preferred_username}"
                                        .equals(m.getConfig().get("template"))),
                "IdP mapper must be embedded in the realm CR with its config");
    }

    @Test
    public void idpConfigUpdateRoundTripsThroughRealmCustomResource() {
        createIdp("update-idp");

        IdentityProviderRepresentation rep =
                realm.admin().identityProviders().get("update-idp").toRepresentation();
        rep.setDisplayName("Renamed Broker");
        rep.setTrustEmail(true);
        rep.getConfig().put("clientId", "kc-broker-v2");
        realm.admin().identityProviders().get("update-idp").update(rep);

        IdentityProviderRepresentation readBack =
                realm.admin().identityProviders().get("update-idp").toRepresentation();
        assertEquals("Renamed Broker", readBack.getDisplayName());
        assertTrue(readBack.isTrustEmail());
        assertEquals("kc-broker-v2", readBack.getConfig().get("clientId"));

        IdentityProviderRepresentation crRep = idpRep("update-idp");
        assertEquals("Renamed Broker", crRep.getDisplayName());
        assertEquals(Boolean.TRUE, crRep.isTrustEmail());
        assertEquals("kc-broker-v2", crRep.getConfig().get("clientId"));
    }

    @Test
    public void deletingIdpRemovesItAndItsMappersFromRealmCustomResource() {
        createIdp("delete-idp");
        IdentityProviderMapperRepresentation mapper = new IdentityProviderMapperRepresentation();
        mapper.setName("delete-idp-mapper");
        mapper.setIdentityProviderAlias("delete-idp");
        mapper.setIdentityProviderMapper("oidc-username-idp-mapper");
        mapper.setConfig(Map.of("syncMode", "INHERIT", "template", "${CLAIM.sub}"));
        try (Response response =
                realm.admin().identityProviders().get("delete-idp").addMapper(mapper)) {
            assertEquals(201, response.getStatus());
        }

        realm.admin().identityProviders().get("delete-idp").remove();

        assertTrue(
                realm.admin().identityProviders().findAll().stream().noneMatch(i -> "delete-idp".equals(i.getAlias())),
                "deleted IdP must disappear from the admin API");
        assertTrue(
                realmCr().getSpec().getIdentityProviders().stream().noneMatch(i -> "delete-idp".equals(i.getAlias())),
                "deleted IdP must disappear from the realm CR");
        assertTrue(
                realmCr().getSpec().getIdentityProviderMappers().stream()
                        .noneMatch(m -> "delete-idp".equals(m.getIdentityProviderAlias())),
                "mappers of a deleted IdP must be cascaded out of the realm CR");
    }
}
