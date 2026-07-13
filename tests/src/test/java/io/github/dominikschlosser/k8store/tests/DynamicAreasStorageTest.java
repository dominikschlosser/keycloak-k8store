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
package io.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.CustomResource;
import io.github.dominikschlosser.k8store.crd.UserSessionSpec;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthSessionCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakLoginFailureCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRevokedTokenCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserSessionCr;
import io.github.dominikschlosser.k8store.tests.config.DynamicAreasServerConfig;
import io.github.dominikschlosser.k8store.tests.framework.Await;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespaces;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.realm.UserConfig;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * Dynamic areas end to end ({@code areas=all}): logging in must create a {@code
 * KeycloakUserSession} CR carrying the embedded client session, logging out must delete it,
 * starting an authorization-code flow must create a {@code KeycloakAuthSession} CR, a failed
 * login on a brute-force-protected realm must create a {@code KeycloakLoginFailure} CR, and
 * revoking an access token must create a {@code KeycloakRevokedToken} CR. Single-use-object CRs
 * are not asserted here: no cheap HTTP round trip exercises them without a full browser login
 * (their provider semantics are covered by the core unit tests).
 */
@Order(1)
@KeycloakIntegrationTest(config = DynamicAreasServerConfig.class)
public class DynamicAreasStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace(ref = TestNamespaces.DYNAMIC_REF)
    TestNamespace namespace;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectUser(config = TestUser.class)
    ManagedUser user;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    private <T extends CustomResource<?, ?>> List<T> crs(Class<T> type) {
        return kube.client()
                .resources(type)
                .inNamespace(namespace.name())
                .list()
                .getItems();
    }

    private String tokenEndpoint() {
        return urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/token";
    }

    private HttpResponse<String> postForm(String url, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode passwordGrant(String password, int expectedStatus) throws Exception {
        String form = "grant_type=password&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpResponse<String> response = postForm(tokenEndpoint(), form);
        assertEquals(expectedStatus, response.statusCode(), () -> "token endpoint answered: " + response.body());
        return JSON.readTree(response.body());
    }

    private List<KeycloakUserSessionCr> sessionsOfTestUser() {
        return crs(KeycloakUserSessionCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && user.getUsername().equals(cr.getSpec().getLoginUsername()))
                .toList();
    }

    @Test
    public void loginCreatesUserSessionCrWithEmbeddedClientSessionAndLogoutDeletesIt() throws Exception {
        JsonNode tokens = passwordGrant(user.getPassword(), 200);
        String sessionId = JSON.readTree(Base64.getUrlDecoder()
                        .decode(tokens.get("access_token").asText().split("\\.")[1]))
                .get("sid")
                .asText();

        Await.await("user session CR of the password-grant login", () -> sessionsOfTestUser().stream()
                .anyMatch(cr -> sessionId.equals(cr.getSpec().getId())));
        UserSessionSpec spec = sessionsOfTestUser().stream()
                .filter(cr -> sessionId.equals(cr.getSpec().getId()))
                .findFirst()
                .orElseThrow()
                .getSpec();
        assertEquals(user.getUsername(), spec.getLoginUsername());
        assertNotNull(spec.getStarted(), "the session CR must carry timestamps");
        assertNotNull(spec.getExpiresAt(), "the session CR must carry a computed expiration");
        assertNotNull(spec.getClientSessions(), "the client session must be embedded in the session CR");
        assertTrue(
                spec.getClientSessions().containsKey("admin-cli"),
                "the embedded client session is keyed by the client's storage id; got: "
                        + spec.getClientSessions().keySet());
        assertNotNull(
                spec.getClientSessions().get("admin-cli").getExpiresAt(),
                "the embedded client session must carry its own expiration");

        String userId = realm.admin().users().search(user.getUsername()).get(0).getId();
        realm.admin().users().get(userId).logout();
        Await.await("user session CRs to be deleted on logout", () -> sessionsOfTestUser()
                .isEmpty());
    }

    @Test
    public void startingAnAuthorizationCodeFlowCreatesAnAuthSessionCr() throws Exception {
        String redirect = urls.getBase() + "/realms/" + realm.getName() + "/account/";
        // account-console enforces PKCE; any well-formed S256 challenge starts the flow
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest("test-verifier".getBytes(StandardCharsets.UTF_8)));
        String authUrl = urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/auth"
                + "?client_id=account-console&response_type=code&scope=openid"
                + "&code_challenge_method=S256&code_challenge=" + challenge
                + "&redirect_uri=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(authUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "auth endpoint answered: " + response.body());

        Await.await("auth session CR of the started flow", () -> crs(KeycloakAuthSessionCr.class).stream()
                .anyMatch(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && cr.getSpec().getTabs() != null
                        && cr.getSpec().getTabs().values().stream()
                                .anyMatch(tab -> "account-console".equals(tab.getClientId()))));
    }

    @Test
    public void failedLoginOnBruteForceProtectedRealmCreatesLoginFailureCr() throws Exception {
        RealmRepresentation rep = realm.admin().toRepresentation();
        rep.setBruteForceProtected(true);
        realm.admin().update(rep);
        try {
            passwordGrant("definitely-wrong-password", 400);
            String userId =
                    realm.admin().users().search(user.getUsername()).get(0).getId();
            Await.await("login failure CR of the failed login", () -> crs(KeycloakLoginFailureCr.class).stream()
                    .anyMatch(cr -> realm.getName().equals(cr.getSpec().getRealm())
                            && userId.equals(cr.getSpec().getUserId())
                            && cr.getSpec().getNumFailures() != null
                            && cr.getSpec().getNumFailures() >= 1));
        } finally {
            rep.setBruteForceProtected(false);
            realm.admin().update(rep);
        }
    }

    @Test
    public void revokingAnAccessTokenCreatesARevokedTokenCr() throws Exception {
        JsonNode tokens = passwordGrant(user.getPassword(), 200);
        String accessToken = tokens.get("access_token").asText();
        String jti = JSON.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]))
                .get("jti")
                .asText();

        String revokeUrl = urls.getBase() + "/realms/" + realm.getName() + "/protocol/openid-connect/revoke";
        HttpResponse<String> response = postForm(
                revokeUrl, "client_id=admin-cli&token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), () -> "revocation endpoint answered: " + response.body());

        Await.await("revoked token CR", () -> crs(KeycloakRevokedTokenCr.class).stream()
                .anyMatch(cr -> jti.equals(cr.getSpec().getTokenId())));
    }

    public static class TestUser implements UserConfig {
        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("dynamo")
                    .password("dynamo-password")
                    .email("dynamo@example.com")
                    .name("Dyna", "Mo");
        }
    }
}
