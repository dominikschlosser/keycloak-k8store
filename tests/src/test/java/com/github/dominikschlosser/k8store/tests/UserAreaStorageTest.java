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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserSessionCr;
import com.github.dominikschlosser.k8store.tests.config.DynamicAreasServerConfig;
import io.fabric8.kubernetes.client.utils.Serialization;
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
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * The experimental {@code user} area end to end ({@code areas=all}): admin-created users become
 * {@code KeycloakUser} custom resources carrying hashed credentials (never plaintext), password
 * logins of CR users work - and still produce the D1 session CRs - admin search/list/count work
 * against the CR store, group membership and role grants land on the user CR, and deleting the
 * user deletes its CR.
 */
@Order(1)
@KeycloakIntegrationTest(config = DynamicAreasServerConfig.class)
public class UserAreaStorageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    private List<KeycloakUserCr> userCrs() {
        return TestKube.client().resources(KeycloakUserCr.class)
                .inNamespace(TestKube.dynamicNamespace()).list().getItems();
    }

    private KeycloakUserCr userCr(String username) {
        return userCrs().stream()
                .filter(cr -> username.equals(cr.getSpec().getUsername())
                        && realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakUser CR with username " + username + "; have: "
                        + userCrs().stream().map(cr -> cr.getSpec().getUsername()).toList()));
    }

    private String createUser(String username, String password) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        user.setEmail(username + "@example.com");
        user.setFirstName("First");
        user.setLastName("Last");
        String userId;
        try (Response response = realm.admin().users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        if (password != null) {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(password);
            credential.setTemporary(false);
            realm.admin().users().get(userId).resetPassword(credential);
        }
        return userId;
    }

    private JsonNode passwordGrant(String username, String password, int expectedStatus) throws Exception {
        String form = "grant_type=password&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), () -> "token endpoint answered: " + response.body());
        return JSON.readTree(response.body());
    }

    @Test
    public void adminCreatedUserBecomesCrWithHashedCredentialsAndNoPlaintext() {
        String password = "Cr-Am4zingly-Secret!";
        String userId = createUser("cr-cred-user", password);

        KeycloakUserCr cr = userCr("cr-cred-user");
        assertEquals(userId, cr.getSpec().getId(), "the admin API id is the CR store id");
        assertEquals("cr-cred-user@example.com", cr.getSpec().getEmail());
        assertEquals("First", cr.getSpec().getFirstName());
        assertNotNull(cr.getSpec().getCreatedTimestamp());

        List<CredentialRepresentation> credentials = cr.getSpec().getCredentials();
        assertNotNull(credentials, "the password credential must live in the user CR");
        assertEquals(1, credentials.size());
        CredentialRepresentation stored = credentials.get(0);
        assertEquals(CredentialRepresentation.PASSWORD, stored.getType());
        assertNotNull(stored.getSecretData(), "hashed secret data must be stored");
        assertNotNull(stored.getCredentialData(), "hash parameters must be stored");
        assertTrue(stored.getCredentialData().contains("hashIterations"), stored.getCredentialData());

        String yaml = Serialization.asYaml(cr);
        assertFalse(yaml.contains(password), "the CR must never contain the plaintext password:\n" + yaml);
    }

    @Test
    public void passwordGrantOfCrUserWorksCaseInsensitivelyAndCreatesSessionCr() throws Exception {
        String userId = createUser("cr-login-user", "cr-login-password");

        // mixed-case login name: usernames are stored lowercased, lookups are case-insensitive
        passwordGrant("CR-Login-USER", "cr-login-password", 200);

        TestKube.await("user session CR of the CR-backed user's login", () -> TestKube.client()
                .resources(KeycloakUserSessionCr.class).inNamespace(TestKube.dynamicNamespace()).list().getItems()
                .stream()
                .anyMatch(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && userId.equals(cr.getSpec().getUserId())));

        // wrong password must fail (same status the D1 brute-force test pinned)
        passwordGrant("cr-login-user", "definitely-wrong", 400);
    }

    @Test
    public void adminSearchListAndCountWorkAgainstTheCrStore() {
        createUser("cr-search-alpha", null);
        createUser("cr-search-beta", null);

        List<UserRepresentation> byPrefix = realm.admin().users().search("cr-search");
        assertTrue(byPrefix.stream().anyMatch(u -> "cr-search-alpha".equals(u.getUsername())), "prefix search");
        assertTrue(byPrefix.stream().anyMatch(u -> "cr-search-beta".equals(u.getUsername())));

        List<UserRepresentation> byEmail = realm.admin().users()
                .searchByEmail("cr-search-alpha@example.com", true);
        assertEquals(1, byEmail.size(), "exact email search");
        assertEquals("cr-search-alpha", byEmail.get(0).getUsername());

        assertTrue(realm.admin().users().count() >= 2, "user count must count the CR store");
        List<UserRepresentation> page = realm.admin().users().list(0, 1);
        assertEquals(1, page.size(), "paging must be honored");
    }

    @Test
    public void groupMembershipAndRoleGrantLandOnTheUserCr() {
        String userId = createUser("cr-member-user", null);

        GroupRepresentation group = new GroupRepresentation();
        group.setName("cr-user-group");
        String groupId;
        try (Response response = realm.admin().groups().add(group)) {
            groupId = CreatedResponseUtil.getCreatedId(response);
        }
        realm.admin().users().get(userId).joinGroup(groupId);

        RoleRepresentation role = new RoleRepresentation();
        role.setName("cr-user-role");
        realm.admin().roles().create(role);
        RoleRepresentation created = realm.admin().roles().get("cr-user-role").toRepresentation();
        realm.admin().users().get(userId).roles().realmLevel().add(List.of(created));

        KeycloakUserCr cr = userCr("cr-member-user");
        assertNotNull(cr.getSpec().getGroups(), "group membership must land on the user CR");
        assertTrue(cr.getSpec().getGroups().contains(groupId),
                "membership is stored as the group id: " + cr.getSpec().getGroups());
        assertNotNull(cr.getSpec().getRealmRoles(), "role grants must land on the user CR");
        assertTrue(cr.getSpec().getRealmRoles().contains("cr-user-role"),
                "grants are stored by role name: " + cr.getSpec().getRealmRoles());

        // and the admin API reads both back through the CR store
        assertTrue(realm.admin().users().get(userId).groups().stream()
                .anyMatch(g -> "cr-user-group".equals(g.getName())));
        assertTrue(realm.admin().users().get(userId).roles().realmLevel().listAll().stream()
                .anyMatch(r -> "cr-user-role".equals(r.getName())));
        assertTrue(realm.admin().groups().group(groupId).members().stream()
                .anyMatch(u -> "cr-member-user".equals(u.getUsername())),
                "group members listing must resolve through the CR-backed membership");

        realm.admin().users().get(userId).leaveGroup(groupId);
        List<String> groupsAfter = userCr("cr-member-user").getSpec().getGroups();
        assertTrue(groupsAfter == null || !groupsAfter.contains(groupId), "leaving must update the CR");
    }

    @Test
    public void deletingTheUserDeletesItsCr() {
        String userId = createUser("cr-doomed-user", "doomed-password");
        assertNotNull(userCr("cr-doomed-user"));

        realm.admin().users().get(userId).remove();

        TestKube.await("user CR to be deleted with the user", () -> userCrs().stream()
                .noneMatch(cr -> "cr-doomed-user".equals(cr.getSpec().getUsername())
                        && realm.getName().equals(cr.getSpec().getRealm())));
    }
}
