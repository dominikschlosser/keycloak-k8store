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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientScopeCr;
import com.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import com.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import com.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import com.github.dominikschlosser.k8store.tests.framework.KindCluster;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Client scope parity: default/optional scope assignment and protocol mappers (on scopes and on
 * clients) must behave like Keycloak's default storage, with every change landing in the
 * KeycloakClient / KeycloakClientScope custom resource specs.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class ClientScopeParityStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    private KeycloakClientCr clientCr(String clientId) {
        return kube.client().resources(KeycloakClientCr.class).inNamespace(namespace.name()).list().getItems().stream()
                .filter(cr -> clientId.equals(cr.getSpec().getClientId())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakClient CR for clientId " + clientId));
    }

    private KeycloakClientScopeCr scopeCr(String name) {
        return kube
                .client()
                .resources(KeycloakClientScopeCr.class)
                .inNamespace(namespace.name())
                .list()
                .getItems()
                .stream()
                .filter(cr -> name.equals(cr.getSpec().getName())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakClientScope CR named " + name));
    }

    private String createClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setEnabled(true);
        try (Response response = realm.admin().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String createScope(String name) {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName(name);
        scope.setProtocol("openid-connect");
        try (Response response = realm.admin().clientScopes().create(scope)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static Optional<ProtocolMapperRepresentation> crMapperById(
            List<ProtocolMapperRepresentation> mappers, String id) {
        return mappers == null
                ? Optional.empty()
                : mappers.stream().filter(m -> id.equals(m.getId())).findFirst();
    }

    private static ProtocolMapperRepresentation hardcodedClaimMapper(String name, String claim, String value) {
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(name);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        Map<String, String> config = new HashMap<>();
        config.put("claim.name", claim);
        config.put("claim.value", value);
        config.put("jsonType.label", "String");
        config.put("access.token.claim", "true");
        config.put("id.token.claim", "true");
        mapper.setConfig(config);
        return mapper;
    }

    @Test
    public void defaultAndOptionalClientScopeAssignmentsLandInClientCustomResource() {
        String defaultScopeId = createScope("assign-default-scope");
        String optionalScopeId = createScope("assign-optional-scope");
        String clientDbId = createClient("scope-assign-client");

        realm.admin().clients().get(clientDbId).addDefaultClientScope(defaultScopeId);
        realm.admin().clients().get(clientDbId).addOptionalClientScope(optionalScopeId);

        assertTrue(
                realm.admin().clients().get(clientDbId).getDefaultClientScopes().stream()
                        .anyMatch(s -> "assign-default-scope".equals(s.getName())),
                "default scope must be readable through getDefaultClientScopes");
        assertTrue(
                realm.admin().clients().get(clientDbId).getOptionalClientScopes().stream()
                        .anyMatch(s -> "assign-optional-scope".equals(s.getName())),
                "optional scope must be readable through getOptionalClientScopes");

        ClientRepresentation crSpec = clientCr("scope-assign-client").getSpec();
        assertTrue(
                crSpec.getDefaultClientScopes().contains("assign-default-scope"),
                "CR spec.defaultClientScopes must list the default scope by name");
        assertTrue(
                crSpec.getOptionalClientScopes().contains("assign-optional-scope"),
                "CR spec.optionalClientScopes must list the optional scope by name");
        assertEquals("assign-default-scope", defaultScopeId, "client scope ids are the scope names in this store");
        assertEquals("assign-optional-scope", optionalScopeId, "client scope ids are the scope names in this store");
    }

    @Test
    public void removingClientScopeAssignmentUpdatesClientCustomResource() {
        String scopeId = createScope("assign-removal-scope");
        String clientDbId = createClient("scope-removal-client");

        realm.admin().clients().get(clientDbId).addDefaultClientScope(scopeId);
        assertTrue(clientCr("scope-removal-client")
                .getSpec()
                .getDefaultClientScopes()
                .contains("assign-removal-scope"));

        realm.admin().clients().get(clientDbId).removeDefaultClientScope(scopeId);

        assertTrue(
                realm.admin().clients().get(clientDbId).getDefaultClientScopes().stream()
                        .noneMatch(s -> "assign-removal-scope".equals(s.getName())),
                "removed scope must disappear from the admin API listing");
        List<String> afterRemove = clientCr("scope-removal-client").getSpec().getDefaultClientScopes();
        assertFalse(
                afterRemove != null && afterRemove.contains("assign-removal-scope"),
                "removed scope must disappear from the client CR");
    }

    @Test
    public void protocolMapperCrudOnClientScopeRoundTripsThroughCustomResource() {
        String scopeId = createScope("mapper-scope");
        String mapperId;
        try (Response response = realm.admin()
                .clientScopes()
                .get(scopeId)
                .getProtocolMappers()
                .createMapper(hardcodedClaimMapper("scope-claim-mapper", "dept", "engineering"))) {
            assertEquals(201, response.getStatus());
            mapperId = CreatedResponseUtil.getCreatedId(response);
        }

        ProtocolMapperRepresentation crMapper = scopeCr("mapper-scope").getSpec().getProtocolMappers().stream()
                .filter(m -> "scope-claim-mapper".equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mapper must land in the scope CR"));
        assertEquals("oidc-hardcoded-claim-mapper", crMapper.getProtocolMapper());
        assertEquals("engineering", crMapper.getConfig().get("claim.value"));

        ProtocolMapperRepresentation update =
                realm.admin().clientScopes().get(scopeId).getProtocolMappers().getMapperById(mapperId);
        update.getConfig().put("claim.value", "platform");
        realm.admin().clientScopes().get(scopeId).getProtocolMappers().update(mapperId, update);
        assertEquals(
                "platform",
                crMapperById(scopeCr("mapper-scope").getSpec().getProtocolMappers(), mapperId)
                        .orElseThrow()
                        .getConfig()
                        .get("claim.value"),
                "mapper update must be reflected in the CR");

        realm.admin().clientScopes().get(scopeId).getProtocolMappers().delete(mapperId);
        assertTrue(realm.admin().clientScopes().get(scopeId).getProtocolMappers().getMappers().stream()
                .noneMatch(m -> "scope-claim-mapper".equals(m.getName())));
        assertTrue(
                crMapperById(scopeCr("mapper-scope").getSpec().getProtocolMappers(), mapperId)
                        .isEmpty(),
                "deleted mapper must leave the scope CR");
    }

    @Test
    public void protocolMapperCrudOnClientRoundTripsThroughCustomResource() {
        String clientDbId = createClient("cs-mapper-client");
        String mapperId;
        try (Response response = realm.admin()
                .clients()
                .get(clientDbId)
                .getProtocolMappers()
                .createMapper(hardcodedClaimMapper("client-claim-mapper", "origin", "direct"))) {
            assertEquals(201, response.getStatus());
            mapperId = CreatedResponseUtil.getCreatedId(response);
        }

        ProtocolMapperRepresentation crMapper = crMapperById(
                        clientCr("cs-mapper-client").getSpec().getProtocolMappers(), mapperId)
                .orElseThrow(() -> new AssertionError("mapper must land in the client CR"));
        assertEquals("client-claim-mapper", crMapper.getName());
        assertEquals("direct", crMapper.getConfig().get("claim.value"));

        ProtocolMapperRepresentation update =
                realm.admin().clients().get(clientDbId).getProtocolMappers().getMapperById(mapperId);
        update.getConfig().put("claim.value", "brokered");
        realm.admin().clients().get(clientDbId).getProtocolMappers().update(mapperId, update);
        assertEquals(
                "brokered",
                crMapperById(clientCr("cs-mapper-client").getSpec().getProtocolMappers(), mapperId)
                        .orElseThrow()
                        .getConfig()
                        .get("claim.value"),
                "mapper update must be reflected in the CR");

        realm.admin().clients().get(clientDbId).getProtocolMappers().delete(mapperId);
        assertTrue(realm.admin().clients().get(clientDbId).getProtocolMappers().getMappers().stream()
                .noneMatch(m -> "client-claim-mapper".equals(m.getName())));
        assertTrue(
                crMapperById(clientCr("cs-mapper-client").getSpec().getProtocolMappers(), mapperId)
                        .isEmpty(),
                "deleted mapper must leave the client CR");
    }

    @Test
    public void clientScopeUpdateRoundTripsThroughCustomResource() {
        String scopeId = createScope("update-scope");
        ClientScopeRepresentation rep =
                realm.admin().clientScopes().get(scopeId).toRepresentation();
        rep.setDescription("updated description");
        rep.setAttributes(Map.of("display.on.consent.screen", "true", "consent.screen.text", "please consent"));
        realm.admin().clientScopes().get(scopeId).update(rep);

        ClientScopeRepresentation readBack =
                realm.admin().clientScopes().get(scopeId).toRepresentation();
        assertEquals("updated description", readBack.getDescription());
        assertEquals("please consent", readBack.getAttributes().get("consent.screen.text"));

        KeycloakClientScopeCr cr = scopeCr("update-scope");
        assertEquals("updated description", cr.getSpec().getDescription());
        assertEquals("openid-connect", cr.getSpec().getProtocol());
        assertEquals(
                "please consent",
                cr.getSpec().getAttributes().get("consent.screen.text"),
                "scope attributes must land in the CR");
    }
}
