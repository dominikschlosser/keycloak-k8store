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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzPolicyCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzResourceCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzScopeCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakResourceServerCr;
import com.github.dominikschlosser.k8store.tests.config.AuthorizationAreasServerConfig;
import io.fabric8.kubernetes.client.CustomResource;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.AuthorizationResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode;
import org.keycloak.representations.idm.authorization.ResourcePermissionRepresentation;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.ResourceServerRepresentation;
import org.keycloak.representations.idm.authorization.RolePolicyRepresentation;
import org.keycloak.representations.idm.authorization.ScopeRepresentation;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * The {@code authorization} area end to end: enabling Authorization Services on a client
 * materializes a {@code KeycloakResourceServer} CR with the default resource/policy CRs, the
 * authorization admin API creates resource/scope/policy/permission CRs with correct
 * cross-references, policy evaluation works through the CR store — a UMA entitlement request
 * (grant {@code urn:ietf:params:oauth:grant-type:uma-ticket}) answers 200 with the granted
 * permissions for a user satisfying the role policy and 403 for one who does not — and
 * deleting the client cascades over the whole authorization CR graph.
 */
@Order(1)
@KeycloakIntegrationTest(config = AuthorizationAreasServerConfig.class)
public class AuthorizationAreaStorageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String CLIENT_SECRET = "authz-secret";

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    // ------------------------------------------------------------------ CR helpers

    private <T extends CustomResource<?, ?>> List<T> crs(Class<T> type) {
        return TestKube.client().resources(type).inNamespace(TestKube.namespace()).list().getItems();
    }

    private List<KeycloakResourceServerCr> resourceServerCrs(String clientId) {
        return crs(KeycloakResourceServerCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && clientId.equals(cr.getSpec().getClientId()))
                .toList();
    }

    private List<KeycloakAuthzResourceCr> resourceCrs(String clientId) {
        return crs(KeycloakAuthzResourceCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && clientId.equals(cr.getSpec().getResourceServer()))
                .toList();
    }

    private List<KeycloakAuthzScopeCr> scopeCrs(String clientId) {
        return crs(KeycloakAuthzScopeCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && clientId.equals(cr.getSpec().getResourceServer()))
                .toList();
    }

    private List<KeycloakAuthzPolicyCr> policyCrs(String clientId) {
        return crs(KeycloakAuthzPolicyCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && clientId.equals(cr.getSpec().getResourceServer()))
                .toList();
    }

    // ------------------------------------------------------------------ admin helpers

    /** Creates a confidential client with Authorization Services enabled; id == clientId. */
    private String createAuthzClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setEnabled(true);
        client.setPublicClient(false);
        client.setSecret(CLIENT_SECRET);
        client.setServiceAccountsEnabled(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setAuthorizationServicesEnabled(true);
        try (Response response = realm.admin().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private AuthorizationResource authorization(String clientUuid) {
        return realm.admin().clients().get(clientUuid).authorization();
    }

    private String createUser(String username, String password, String realmRole) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        // a complete profile — an unset email/name would leave the VERIFY_PROFILE required
        // action pending and fail the password grant with "Account is not fully set up"
        user.setEmail(username + "@example.com");
        user.setFirstName("First");
        user.setLastName("Last");
        String userId;
        try (Response response = realm.admin().users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        realm.admin().users().get(userId).resetPassword(credential);
        if (realmRole != null) {
            RoleRepresentation role = realm.admin().roles().get(realmRole).toRepresentation();
            realm.admin().users().get(userId).roles().realmLevel().add(List.of(role));
        }
        return userId;
    }

    private void createRealmRoleIfAbsent(String name) {
        if (realm.admin().roles().list().stream().noneMatch(r -> name.equals(r.getName()))) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(name);
            realm.admin().roles().create(role);
        }
    }

    // ------------------------------------------------------------------ token helpers

    private JsonNode tokenRequest(String form, String bearerToken, int expectedStatus) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), () -> "token endpoint answered: " + response.body());
        return JSON.readTree(response.body());
    }

    private String passwordGrant(String clientId, String username, String password) throws Exception {
        String form = "grant_type=password"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8)
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return tokenRequest(form, null, 200).get("access_token").asText();
    }

    /** UMA entitlement request: all permissions of the audience, answered as a permission list. */
    private JsonNode umaEntitlement(String audience, String bearerToken, int expectedStatus) throws Exception {
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:uma-ticket",
                        StandardCharsets.UTF_8)
                + "&audience=" + URLEncoder.encode(audience, StandardCharsets.UTF_8)
                + "&response_mode=permissions";
        return tokenRequest(form, bearerToken, expectedStatus);
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void enablingAuthorizationServicesCreatesResourceServerCr() {
        String clientUuid = createAuthzClient("authz-enable-client");
        assertEquals("authz-enable-client", clientUuid, "this store's client id is the clientId");

        List<KeycloakResourceServerCr> servers = resourceServerCrs("authz-enable-client");
        assertEquals(1, servers.size(), "enabling authorization services must create the resource-server CR");
        assertEquals(PolicyEnforcementMode.ENFORCING, servers.get(0).getSpec().getPolicyEnforcementMode());
        // the uma_protection role of the resource server lands in the role area as usual
        assertNotNull(realm.admin().clients().get(clientUuid).roles().get("uma_protection").toRepresentation());

        // the admin API reads the resource server back through the CR store, and settings
        // updates land on the CR
        ResourceServerRepresentation settings = authorization(clientUuid).getSettings();
        assertNotNull(settings);
        settings.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);
        settings.setAllowRemoteResourceManagement(true);
        authorization(clientUuid).update(settings);
        TestKube.await("resource-server CR to carry the updated settings",
                () -> resourceServerCrs("authz-enable-client").stream().anyMatch(cr ->
                        DecisionStrategy.AFFIRMATIVE == cr.getSpec().getDecisionStrategy()
                                && Boolean.TRUE.equals(cr.getSpec().getAllowRemoteResourceManagement())));
    }

    @Test
    public void authzAdminApiCreatesCrossReferencedCrs() {
        String clientUuid = createAuthzClient("authz-crud-client");
        AuthorizationResource authorization = authorization(clientUuid);

        ScopeRepresentation scope = new ScopeRepresentation("docs:read");
        try (Response response = authorization.scopes().create(scope)) {
            assertEquals(201, response.getStatus());
        }
        KeycloakAuthzScopeCr scopeCr = scopeCrs("authz-crud-client").stream()
                .filter(cr -> "docs:read".equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakAuthzScope CR named docs:read"));

        ResourceRepresentation resource = new ResourceRepresentation("Document Resource", "docs:read");
        try (Response response = authorization.resources().create(resource)) {
            assertEquals(201, response.getStatus());
        }
        KeycloakAuthzResourceCr resourceCr = resourceCrs("authz-crud-client").stream()
                .filter(cr -> "Document Resource".equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakAuthzResource CR named Document Resource"));
        assertNotNull(resourceCr.getSpec().getScopeIds(), "the resource CR must reference its scope");
        assertTrue(resourceCr.getSpec().getScopeIds().contains(scopeCr.getSpec().getId()),
                "the resource CR references the scope by id: " + resourceCr.getSpec().getScopeIds());

        createRealmRoleIfAbsent("doc-reader");
        RolePolicyRepresentation rolePolicy = new RolePolicyRepresentation();
        rolePolicy.setName("doc-reader-policy");
        rolePolicy.addRole("doc-reader", true);
        try (Response response = authorization.policies().role().create(rolePolicy)) {
            assertEquals(201, response.getStatus());
        }
        KeycloakAuthzPolicyCr policyCr = policyCrs("authz-crud-client").stream()
                .filter(cr -> "doc-reader-policy".equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakAuthzPolicy CR named doc-reader-policy"));
        assertEquals("role", policyCr.getSpec().getType());
        assertNotNull(policyCr.getSpec().getConfig());
        assertTrue(policyCr.getSpec().getConfig().get("roles").contains("doc-reader"),
                "the role policy's roles config must reference the role: " + policyCr.getSpec().getConfig());

        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();
        permission.setName("document-permission");
        permission.addResource("Document Resource");
        permission.addPolicy("doc-reader-policy");
        try (Response response = authorization.permissions().resource().create(permission)) {
            assertEquals(201, response.getStatus());
        }
        KeycloakAuthzPolicyCr permissionCr = policyCrs("authz-crud-client").stream()
                .filter(cr -> "document-permission".equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakAuthzPolicy CR named document-permission"));
        assertEquals("resource", permissionCr.getSpec().getType());
        assertTrue(permissionCr.getSpec().getResourceIds().contains(resourceCr.getSpec().getId()),
                "the permission CR references the resource by id");
        assertTrue(permissionCr.getSpec().getAssociatedPolicyIds().contains(policyCr.getSpec().getId()),
                "the permission CR references the role policy by id");
    }

    @Test
    public void policyEvaluationGrantsAndDeniesThroughTheCrStore() throws Exception {
        String clientUuid = createAuthzClient("authz-eval-client");
        AuthorizationResource authorization = authorization(clientUuid);

        createRealmRoleIfAbsent("eval-reader");
        RolePolicyRepresentation rolePolicy = new RolePolicyRepresentation();
        rolePolicy.setName("eval-reader-policy");
        rolePolicy.addRole("eval-reader", true);
        try (Response response = authorization.policies().role().create(rolePolicy)) {
            assertEquals(201, response.getStatus());
        }

        ResourceRepresentation resource = new ResourceRepresentation("Evaluated Resource");
        try (Response response = authorization.resources().create(resource)) {
            assertEquals(201, response.getStatus());
        }

        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();
        permission.setName("evaluated-permission");
        permission.addResource("Evaluated Resource");
        permission.addPolicy("eval-reader-policy");
        try (Response response = authorization.permissions().resource().create(permission)) {
            assertEquals(201, response.getStatus());
        }

        createUser("eval-reader-user", "eval-password", "eval-reader");
        createUser("eval-outsider-user", "eval-password", null);

        // the user with the role obtains an entitlement listing the protected resource
        String readerToken = passwordGrant("authz-eval-client", "eval-reader-user", "eval-password");
        // regression guard for the default-scope assignment during client creation: without the
        // realm's default scopes the token has no realm_access and every role policy denies
        assertTrue(realm.admin().clients().get(clientUuid).getDefaultClientScopes().stream()
                        .anyMatch(s -> "roles".equals(s.getName())),
                "a new client must inherit the realm's default client scopes");
        JsonNode permissions = umaEntitlement("authz-eval-client", readerToken, 200);
        assertTrue(permissions.isArray() && permissions.size() > 0, "granted permissions expected: " + permissions);
        boolean grantsEvaluatedResource = false;
        for (JsonNode granted : permissions) {
            grantsEvaluatedResource |= "Evaluated Resource".equals(granted.path("rsname").asText());
        }
        assertTrue(grantsEvaluatedResource, "the RPT permissions must contain the resource: " + permissions);

        // the user without the role is denied
        String outsiderToken = passwordGrant("authz-eval-client", "eval-outsider-user", "eval-password");
        JsonNode denied = umaEntitlement("authz-eval-client", outsiderToken, 403);
        assertEquals("access_denied", denied.path("error").asText(), denied::toString);
    }

    @Test
    public void deletionsCascadeOverTheAuthorizationCrGraph() {
        String clientUuid = createAuthzClient("authz-cascade-client");
        AuthorizationResource authorization = authorization(clientUuid);

        createRealmRoleIfAbsent("cascade-role");
        RolePolicyRepresentation rolePolicy = new RolePolicyRepresentation();
        rolePolicy.setName("cascade-role-policy");
        rolePolicy.addRole("cascade-role", false);
        try (Response response = authorization.policies().role().create(rolePolicy)) {
            assertEquals(201, response.getStatus());
        }
        ResourceRepresentation resource = new ResourceRepresentation("Cascade Resource");
        try (Response response = authorization.resources().create(resource)) {
            assertEquals(201, response.getStatus());
        }
        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();
        permission.setName("cascade-permission");
        permission.addResource("Cascade Resource");
        permission.addPolicy("cascade-role-policy");
        try (Response response = authorization.permissions().resource().create(permission)) {
            assertEquals(201, response.getStatus());
        }

        // deleting the only resource of a permission deletes the permission too (upstream
        // cascade through the store SPI) and the resource CR disappears
        String resourceId = resourceCrs("authz-cascade-client").stream()
                .filter(cr -> "Cascade Resource".equals(cr.getSpec().getName()))
                .findFirst()
                .orElseThrow()
                .getSpec()
                .getId();
        authorization.resources().resource(resourceId).remove();
        TestKube.await("resource CR to be deleted", () -> resourceCrs("authz-cascade-client").stream()
                .noneMatch(cr -> "Cascade Resource".equals(cr.getSpec().getName())));
        TestKube.await("dependent permission CR to be deleted with its only resource",
                () -> policyCrs("authz-cascade-client").stream()
                        .noneMatch(cr -> "cascade-permission".equals(cr.getSpec().getName())));

        // deleting the client deletes the whole remaining graph
        realm.admin().clients().get(clientUuid).remove();
        TestKube.await("resource-server CR to be deleted with the client",
                () -> resourceServerCrs("authz-cascade-client").isEmpty());
        TestKube.await("all policy CRs to be deleted with the client",
                () -> policyCrs("authz-cascade-client").isEmpty());
        TestKube.await("all resource CRs to be deleted with the client",
                () -> resourceCrs("authz-cascade-client").isEmpty());
        TestKube.await("all scope CRs to be deleted with the client",
                () -> scopeCrs("authz-cascade-client").isEmpty());
    }
}
