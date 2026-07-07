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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakIssuedVerifiableCredentialCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserVerifiableCredentialCr;
import com.github.dominikschlosser.k8store.tests.config.DynamicAreasServerConfig;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.models.IssuedVerifiableCredentialModel;
import org.keycloak.models.UserVerifiableCredentialModel;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.remote.runonserver.InjectRunOnServer;
import org.keycloak.testframework.remote.runonserver.RunOnServerClient;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * OID4VC verifiable-credential storage of the {@code user} area (experimental {@code oid4vc-vci}
 * feature enabled on the server): user verifiable credentials created through the admin API
 * ({@code users/{id}/vc/credentials}) become {@code KeycloakUserVerifiableCredential} CRs with
 * upstream semantics (generated ids, revision rolling on update, attribute snapshots), and
 * issued credentials — driven through the provider SPI via run-on-server, no full issuance flow
 * needed — become expiring {@code KeycloakIssuedVerifiableCredential} CRs that die with their
 * verifiable credential.
 */
@Order(1)
@KeycloakIntegrationTest(config = DynamicAreasServerConfig.class)
public class Oid4vcAreaStorageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SCOPE_NAME = "university-degree";

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    @InjectAdminClient
    Keycloak adminClient;

    @InjectRunOnServer
    RunOnServerClient runOnServer;

    private List<KeycloakUserVerifiableCredentialCr> credentialCrs() {
        return TestKube.client().resources(KeycloakUserVerifiableCredentialCr.class)
                .inNamespace(TestKube.dynamicNamespace()).list().getItems().stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm()))
                .toList();
    }

    private List<KeycloakIssuedVerifiableCredentialCr> issuedCrs() {
        return TestKube.client().resources(KeycloakIssuedVerifiableCredentialCr.class)
                .inNamespace(TestKube.dynamicNamespace()).list().getItems().stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm()))
                .toList();
    }

    private void enableRealmVcAndCreateCredentialScope() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        if (!Boolean.TRUE.equals(rep.isVerifiableCredentialsEnabled())) {
            rep.setVerifiableCredentialsEnabled(true);
            realm.admin().update(rep);
        }
        boolean scopeExists = realm.admin().clientScopes().findAll().stream()
                .anyMatch(scope -> SCOPE_NAME.equals(scope.getName()));
        if (!scopeExists) {
            ClientScopeRepresentation scope = new ClientScopeRepresentation();
            scope.setName(SCOPE_NAME);
            scope.setProtocol("oid4vc");
            try (Response response = realm.admin().clientScopes().create(scope)) {
                assertEquals(201, response.getStatus());
            }
        }
    }

    private String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        user.setEmail(username + "@example.com");
        user.setFirstName("Vc");
        user.setLastName("Holder");
        try (Response response = realm.admin().users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private HttpResponse<String> vcRequest(String method, String userId, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        urls.getBase() + "/admin/realms/" + realm.getName() + "/users/" + userId + "/vc/" + path))
                .header("Authorization", "Bearer " + adminClient.tokenManager().getAccessTokenString())
                .header("Content-Type", "application/json");
        request = switch (method) {
            case "POST" -> request.POST(HttpRequest.BodyPublishers.ofString(body));
            case "PUT" -> request.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "DELETE" -> request.DELETE();
            default -> request.GET();
        };
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void adminManagedVerifiableCredentialLifecycleLandsOnCrs() throws Exception {
        enableRealmVcAndCreateCredentialScope();
        String userId = createUser("vc-user");

        // create through the feature's admin endpoint — the storage behind it is the CR store
        HttpResponse<String> created = vcRequest("POST", userId, "credentials",
                "{\"credentialScopeName\":\"" + SCOPE_NAME + "\"}");
        assertEquals(200, created.statusCode(), () -> "creating the credential failed: " + created.body());
        JsonNode createdRep = JSON.readTree(created.body());
        assertEquals(SCOPE_NAME, createdRep.path("credentialScopeName").asText());
        String initialRevision = createdRep.path("revision").asText();
        assertFalse(initialRevision.isBlank(), "a revision must be generated");

        KeycloakUserVerifiableCredentialCr cr = credentialCrs().stream()
                .filter(candidate -> userId.equals(candidate.getSpec().getUserId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakUserVerifiableCredential CR for the user"));
        assertEquals(SCOPE_NAME, cr.getSpec().getClientScopeId(),
                "credential scopes are client scopes — scope id == scope name in this store");
        assertEquals(initialRevision, cr.getSpec().getRevision());
        assertNotNull(cr.getSpec().getCreatedDate());
        assertNotNull(cr.getSpec().getUserAttributes(), "the profile attribute snapshot must be stored");
        assertTrue(cr.getSpec().getUserAttributes().containsKey("firstName"),
                "snapshot must carry the readable profile attributes: " + cr.getSpec().getUserAttributes());

        // list + update: the revision rolls, the snapshot refreshes
        HttpResponse<String> listed = vcRequest("GET", userId, "credentials", null);
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains(SCOPE_NAME), listed.body());

        HttpResponse<String> updated = vcRequest("PUT", userId, "credentials/" + SCOPE_NAME, null);
        assertEquals(200, updated.statusCode(), () -> "updating the credential failed: " + updated.body());
        String rolledRevision = JSON.readTree(updated.body()).path("revision").asText();
        assertNotEquals(initialRevision, rolledRevision, "an update must roll the revision");
        assertEquals(rolledRevision, credentialCrs().stream()
                .filter(candidate -> userId.equals(candidate.getSpec().getUserId()))
                .findFirst().orElseThrow().getSpec().getRevision());

        // revoke deletes the CR
        assertEquals(204, vcRequest("DELETE", userId, "credentials/" + SCOPE_NAME, null).statusCode());
        TestKube.await("verifiable-credential CR to be deleted on revocation", () -> credentialCrs().stream()
                .noneMatch(candidate -> userId.equals(candidate.getSpec().getUserId())));
    }

    @Test
    public void issuedCredentialsAreExpiringCrsAndDieWithTheirCredential() throws Exception {
        enableRealmVcAndCreateCredentialScope();
        String userId = createUser("vc-issued-user");
        String realmName = realm.getName();

        // drive the issuance-side SPI directly (a full OID4VC wallet flow needs key material and
        // a wallet — disproportionate here; the storage contract is what this store implements)
        String report = runOnServer.fetchString(session -> {
            var realmModel = session.realms().getRealmByName(realmName);
            session.getContext().setRealm(realmModel);
            var user = session.users().getUserByUsername(realmModel, "vc-issued-user");

            UserVerifiableCredentialModel credential = new UserVerifiableCredentialModel(null, SCOPE_NAME);
            credential = session.users().addVerifiableCredential(user.getId(), credential);

            IssuedVerifiableCredentialModel issued = new IssuedVerifiableCredentialModel();
            issued.setUserId(user.getId());
            issued.setVerifiableCredentialId(credential.getId());
            issued.setClientId("wallet-client");
            issued.setExpiresAt(System.currentTimeMillis() + 3_600_000L);
            issued = session.users().addIssuedVerifiableCredential(issued);

            long issuedCount = session.users().getIssuedVerifiableCredentialsStreamByUser(user.getId()).count();
            return "credentialId=" + credential.getId() + " credentialRevision=" + credential.getRevision()
                    + " issuedId=" + issued.getId() + " issuedRevision=" + issued.getRevision()
                    + " issuedCount=" + issuedCount;
        });
        assertTrue(report.contains("issuedCount=1"), report);

        KeycloakIssuedVerifiableCredentialCr issuedCr = issuedCrs().stream()
                .filter(candidate -> userId.equals(candidate.getSpec().getUserId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakIssuedVerifiableCredential CR; report: " + report));
        assertEquals("wallet-client", issuedCr.getSpec().getClientId());
        assertNotNull(issuedCr.getSpec().getExpiresAt(), "issuances expire — expiresAt drives the reaper");
        assertTrue(report.contains("issuedRevision=" + issuedCr.getSpec().getRevision()));
        assertTrue(report.contains("credentialRevision=" + issuedCr.getSpec().getRevision()),
                "an issuance without an explicit revision inherits the credential's revision");

        // removing the verifiable credential removes its issuances too (upstream parity)
        String removal = runOnServer.fetchString(session -> {
            var realmModel = session.realms().getRealmByName(realmName);
            session.getContext().setRealm(realmModel);
            var user = session.users().getUserByUsername(realmModel, "vc-issued-user");
            // the removal addresses the credential by its scope id (upstream semantics)
            boolean removed = session.users().removeVerifiableCredential(user.getId(), SCOPE_NAME);
            return "removed=" + removed;
        });
        assertTrue(removal.contains("removed=true"), removal);
        TestKube.await("credential and issuance CRs to be deleted together", () ->
                credentialCrs().stream().noneMatch(candidate -> userId.equals(candidate.getSpec().getUserId()))
                        && issuedCrs().stream().noneMatch(candidate ->
                                userId.equals(candidate.getSpec().getUserId())));
    }
}
