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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserCr;
import com.github.dominikschlosser.k8store.tests.config.DynamicAreasServerConfig;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.models.UserConsentModel;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.remote.runonserver.InjectRunOnServer;
import org.keycloak.testframework.remote.runonserver.RunOnServerClient;

/**
 * Consent scope-parameters of the experimental {@code parameterized-scopes} feature (enabled on
 * the server under test): granting a parameterized scope with parameters persists them in the
 * user CR's consent entry ({@code grantedScopeParameters}), and the consent model reads them
 * back — driven through the provider SPI via run-on-server, because the admin consent
 * representation has no parameter surface (nothing to exercise over REST).
 */
@Order(1)
@KeycloakIntegrationTest(config = DynamicAreasServerConfig.class)
public class ConsentParametersStorageTest {

    private static final String PARAMETERIZED_SCOPE = "tenant";
    private static final String PLAIN_SCOPE = "plain-consent-scope";
    private static final String CLIENT_ID = "consent-params-client";
    private static final String USERNAME = "consent-params-user";

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectRunOnServer
    RunOnServerClient runOnServer;

    @Test
    public void parameterizedScopeConsentPersistsItsParametersOnTheUserCr() {
        // fixture: a client, a parameterized scope (the feature's marker attribute), a plain
        // scope and a user — all CR-backed
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(CLIENT_ID);
        client.setEnabled(true);
        try (Response response = realm.admin().clients().create(client)) {
            assertEquals(201, response.getStatus());
        }
        ClientScopeRepresentation parameterized = new ClientScopeRepresentation();
        parameterized.setName(PARAMETERIZED_SCOPE);
        parameterized.setProtocol("openid-connect");
        // the feature's markers: parameterized + a parameter type (validated at creation)
        parameterized.setAttributes(java.util.Map.of(
                "is.parameterized.scope", "true",
                "parameterized.scope.type", "string"));
        try (Response response = realm.admin().clientScopes().create(parameterized)) {
            assertEquals(201, response.getStatus());
        }
        ClientScopeRepresentation plain = new ClientScopeRepresentation();
        plain.setName(PLAIN_SCOPE);
        plain.setProtocol("openid-connect");
        try (Response response = realm.admin().clientScopes().create(plain)) {
            assertEquals(201, response.getStatus());
        }
        UserRepresentation user = new UserRepresentation();
        user.setUsername(USERNAME);
        user.setEnabled(true);
        String userId;
        try (Response response = realm.admin().users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }

        String realmName = realm.getName();

        // grant: a parameterized scope with two parameters plus a plain scope
        String granted = runOnServer.fetchString(session -> {
            var realmModel = session.realms().getRealmByName(realmName);
            session.getContext().setRealm(realmModel);
            var clientModel = session.clients().getClientByClientId(realmModel, CLIENT_ID);
            var scope = session.clientScopes().getClientScopesStream(realmModel)
                    .filter(candidate -> PARAMETERIZED_SCOPE.equals(candidate.getName()))
                    .findFirst().orElseThrow();
            var plainScope = session.clientScopes().getClientScopesStream(realmModel)
                    .filter(candidate -> PLAIN_SCOPE.equals(candidate.getName()))
                    .findFirst().orElseThrow();
            var userModel = session.users().getUserByUsername(realmModel, USERNAME);

            UserConsentModel consent = new UserConsentModel(clientModel);
            consent.addGrantedClientScope(scope, "acme");
            consent.addGrantedClientScope(scope, "globex");
            consent.addGrantedClientScope(plainScope);
            session.users().addConsent(realmModel, userModel.getId(), consent);
            return "granted scopes=" + consent.getGrantedClientScopes().size();
        });
        assertTrue(granted.contains("scopes=2"), granted);

        // the CR carries the parameters in the consent entry
        KeycloakUserCr cr = TestKube.client().resources(KeycloakUserCr.class)
                .inNamespace(TestKube.dynamicNamespace()).list().getItems().stream()
                .filter(candidate -> USERNAME.equals(candidate.getSpec().getUsername())
                        && realmName.equals(candidate.getSpec().getRealm()))
                .findFirst().orElseThrow();
        assertNotNull(cr.getSpec().getConsents(), "the consent entry must land on the user CR");
        assertEquals(1, cr.getSpec().getConsents().size());
        var consentSpec = cr.getSpec().getConsents().get(0);
        assertEquals(CLIENT_ID, consentSpec.getClientId());
        assertTrue(consentSpec.getGrantedClientScopes().contains(PARAMETERIZED_SCOPE));
        assertTrue(consentSpec.getGrantedClientScopes().contains(PLAIN_SCOPE));
        assertNotNull(consentSpec.getGrantedScopeParameters(), "scope parameters must persist");
        assertEquals(List.of("acme", "globex"),
                consentSpec.getGrantedScopeParameters().get(PARAMETERIZED_SCOPE));

        // read back through the consent model: parameters, membership checks, plain scope
        String read = runOnServer.fetchString(session -> {
            var realmModel = session.realms().getRealmByName(realmName);
            session.getContext().setRealm(realmModel);
            var clientModel = session.clients().getClientByClientId(realmModel, CLIENT_ID);
            var scope = session.clientScopes().getClientScopesStream(realmModel)
                    .filter(candidate -> PARAMETERIZED_SCOPE.equals(candidate.getName()))
                    .findFirst().orElseThrow();
            var userModel = session.users().getUserByUsername(realmModel, USERNAME);
            UserConsentModel consent =
                    session.users().getConsentByClient(realmModel, userModel.getId(), clientModel.getId());
            return "parameters=" + consent.getParameters(scope)
                    + " acmeGranted=" + consent.isClientScopeGranted(scope, "acme")
                    + " otherGranted=" + consent.isClientScopeGranted(scope, "hooli")
                    + " scopes=" + consent.getGrantedClientScopes().size();
        });
        assertTrue(read.contains("parameters=[acme, globex]"), read);
        assertTrue(read.contains("acmeGranted=true"), read);
        assertTrue(read.contains("otherGranted=false"), read);
        assertTrue(read.contains("scopes=2"), read);

        // updating the consent replaces the parameter set
        String updated = runOnServer.fetchString(session -> {
            var realmModel = session.realms().getRealmByName(realmName);
            session.getContext().setRealm(realmModel);
            var clientModel = session.clients().getClientByClientId(realmModel, CLIENT_ID);
            var scope = session.clientScopes().getClientScopesStream(realmModel)
                    .filter(candidate -> PARAMETERIZED_SCOPE.equals(candidate.getName()))
                    .findFirst().orElseThrow();
            var userModel = session.users().getUserByUsername(realmModel, USERNAME);
            UserConsentModel replacement = new UserConsentModel(clientModel);
            replacement.addGrantedClientScope(scope, "initech");
            session.users().updateConsent(realmModel, userModel.getId(), replacement);
            return "updated";
        });
        assertEquals("updated", updated.replace("\"", ""));

        var updatedConsent = TestKube.client().resources(KeycloakUserCr.class)
                .inNamespace(TestKube.dynamicNamespace()).list().getItems().stream()
                .filter(candidate -> USERNAME.equals(candidate.getSpec().getUsername())
                        && realmName.equals(candidate.getSpec().getRealm()))
                .findFirst().orElseThrow()
                .getSpec().getConsents().get(0);
        assertEquals(List.of("initech"),
                updatedConsent.getGrantedScopeParameters().get(PARAMETERIZED_SCOPE),
                "an update must replace the stored parameters");
        assertEquals(List.of(PARAMETERIZED_SCOPE), updatedConsent.getGrantedClientScopes());
    }
}
