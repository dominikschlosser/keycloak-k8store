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
package com.github.dominikschlosser.k8store.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.crd.SingleUseObjectSpec;
import com.github.dominikschlosser.k8store.crd.UserConsentSpec;
import com.github.dominikschlosser.k8store.crd.UserSessionSpec;
import com.github.dominikschlosser.k8store.crd.UserSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserSessionCr;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.Time;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.storage.ReadOnlyException;

/**
 * Dynamic-kind behavior of the storage backend: area parsing, gated informer registration,
 * writability under read-only mode, expired-entity filtering on read and the background reaper.
 */
@EnableKubernetesMockClient(crud = true)
class K8sDynamicKindsTest {

    KubernetesClient client;

    @AfterEach
    void shutdownBackend() {
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();
    }

    private K8sStorageBackend start(boolean readOnly, Set<Area> areas) {
        return K8sStorageBackend.initWithClient(client, K8sStoreConfig.of(readOnly, areas, "test", false, 30));
    }

    // ------------------------------------------------------------------ area parsing

    @Test
    void absentBlankAndConfigAreaValuesMeanTheConfigAreas() {
        Set<Area> expected = K8sStoreConfig.configAreas();
        assertEquals(expected, K8sStoreConfig.parseAreas(null));
        assertEquals(expected, K8sStoreConfig.parseAreas("  "));
        assertEquals(expected, K8sStoreConfig.parseAreas("config"));
        assertEquals(expected, K8sStoreConfig.parseAreas("CONFIG"));
        assertTrue(expected.stream().noneMatch(Area::isDynamic),
                "the default areas must never activate a dynamic area");
    }

    @Test
    void allAreaValueMeansEverythingIncludingDynamicAreas() {
        assertEquals(EnumSet.allOf(Area.class), K8sStoreConfig.parseAreas("all"));
        assertTrue(K8sStoreConfig.parseAreas("all").contains(Area.USER),
                "the user area joins 'all'");
        assertFalse(K8sStoreConfig.configAreas().contains(Area.USER),
                "users are a dynamic area (runtime-mutated), never part of the config default");
    }

    @Test
    void explicitAreaListsAreParsedVerbatim() {
        assertEquals(EnumSet.of(Area.REALM, Area.USER_SESSION, Area.SINGLE_USE_OBJECT),
                K8sStoreConfig.parseAreas("realm, user-session,single-use-object"));
        assertThrows(IllegalArgumentException.class, () -> K8sStoreConfig.parseAreas("realm,bogus"));
    }

    // ------------------------------------------------------------------ registration gating

    @Test
    void configOnlyAreasRegisterNoDynamicKinds() {
        K8sStorageBackend backend = start(false, K8sStoreConfig.configAreas());
        assertThrows(IllegalArgumentException.class,
                () -> backend.read(UserSessionSpec.class, "master", "some-id"),
                "with config-only areas the dynamic kinds must not be registered (no informers, no CRDs needed)");
        assertThrows(IllegalArgumentException.class,
                () -> backend.read(UserSpec.class, "master", "some-user"),
                "the user kind is dynamic too: not registered without the user area");
    }

    // ------------------------------------------------------------------ writability

    @Test
    void dynamicKindsStayWritableInReadOnlyMode() {
        start(true, EnumSet.allOf(Area.class));

        UserSessionSpec session = new UserSessionSpec();
        session.setId("session-1");
        session.setRealm("master");
        session.setUserId("user-1");
        K8sStorageBackend.update(UserSessionSpec.class, "master", "session-1", session);
        assertEquals(1, client.resources(KeycloakUserSessionCr.class).inNamespace("test").list().getItems().size(),
                "dynamic-kind writes must reach the API server despite read-only mode");
        K8sStorageBackend.delete(UserSessionSpec.class, "master", "session-1");
        assertTrue(client.resources(KeycloakUserSessionCr.class).inNamespace("test").list().getItems().isEmpty());

        RoleSpec role = new RoleSpec();
        role.setId("role-1");
        role.setRealm("master");
        role.setName("role-1");
        assertThrows(ReadOnlyException.class,
                () -> K8sStorageBackend.update(RoleSpec.class, "master", "role-1", role),
                "config-kind writes must still be rejected in read-only mode");
    }

    // ------------------------------------------------------------------ expiration

    private KeycloakUserSessionCr userSessionCr(String id, long expiresAt) {
        UserSessionSpec spec = new UserSessionSpec();
        spec.setId(id);
        spec.setRealm("master");
        spec.setUserId("user-1");
        spec.setExpiresAt(expiresAt);
        KeycloakUserSessionCr cr = new KeycloakUserSessionCr();
        cr.setMetadata(new ObjectMetaBuilder().withName(id).withNamespace("test").build());
        cr.setSpec(spec);
        return cr;
    }

    @Test
    void expiredEntitiesAreFilteredOnEveryRead() {
        K8sStorageBackend backend = start(false, EnumSet.allOf(Area.class));
        long now = Time.currentTimeMillis();
        client.resource(userSessionCr("expired", now - 1000)).create();
        client.resource(userSessionCr("live", now + 60_000)).create();

        await(() -> backend.read(UserSessionSpec.class, "master", "live") != null);
        assertNull(backend.read(UserSessionSpec.class, "master", "expired"),
                "an expired entity must never be handed to the model layer");
        assertEquals(1, backend.readAllInRealm(UserSessionSpec.class, "master").size());
        assertNull(backend.fetch(UserSessionSpec.class, "master", "expired"));
    }

    @Test
    void neverExpiringEntitiesAreServed() {
        K8sStorageBackend backend = start(false, EnumSet.allOf(Area.class));
        client.resource(userSessionCr("forever", 0)).create();
        await(() -> backend.read(UserSessionSpec.class, "master", "forever") != null);
        assertNotNull(backend.read(UserSessionSpec.class, "master", "forever"),
                "expiresAt of 0 means the entity never expires");
    }

    @Test
    void reaperDeletesExpiredCrs() {
        K8sStorageBackend backend = start(false, EnumSet.allOf(Area.class));
        long now = Time.currentTimeMillis();
        client.resource(userSessionCr("expired", now - 1000)).create();
        client.resource(userSessionCr("live", now + 60_000)).create();
        await(() -> backend.read(UserSessionSpec.class, "master", "live") != null);
        await(() -> !backend.readAllInRealm(UserSessionSpec.class, "master").isEmpty());

        backend.sweepExpired();

        var remaining = client.resources(KeycloakUserSessionCr.class).inNamespace("test").list().getItems();
        assertEquals(1, remaining.size(), "the reaper must delete expired CRs and only those");
        assertEquals("live", remaining.get(0).getSpec().getId());
    }

    // ------------------------------------------------------------------ user kind

    @Test
    void userSpecRoundTripsWithHashedCredentialsAndWithoutNulls() throws Exception {
        start(true, EnumSet.allOf(Area.class)); // read-only mode: user CRs stay writable

        UserSpec spec = new UserSpec();
        spec.setId("alice");
        spec.setRealm("master");
        spec.setUsername("alice");
        spec.setEmail("alice@example.com");
        spec.setEnabled(true);
        spec.setAttributes(new HashMap<>());
        spec.getAttributes().put("dept", List.of("engineering"));
        spec.getAttributes().put("absent", null);
        CredentialRepresentation password = new CredentialRepresentation();
        password.setId("cred-1");
        password.setType("password");
        password.setCreatedDate(123L);
        password.setSecretData("{\"value\":\"hashed\",\"salt\":\"c2FsdA==\"}");
        password.setCredentialData("{\"hashIterations\":27500,\"algorithm\":\"pbkdf2-sha512\"}");
        spec.setCredentials(new ArrayList<>(List.of(password)));

        K8sStorageBackend.update(UserSpec.class, "master", "alice", spec);

        // the credential entries survive the mirror round trip intact - hashes, never plaintext
        UserSpec read = K8sStorageBackend.get().read(UserSpec.class, "master", "alice");
        assertNotNull(read);
        assertEquals(1, read.getCredentials().size());
        assertEquals("password", read.getCredentials().get(0).getType());
        assertEquals(password.getSecretData(), read.getCredentials().get(0).getSecretData());
        assertEquals(password.getCredentialData(), read.getCredentials().get(0).getCredentialData());
        assertEquals(1, client.resources(KeycloakUserCr.class).inNamespace("test").list().getItems().size());

        // the write client's serialization drops null properties and null map values (422 on a
        // real API server otherwise) and never carries the excluded runtime/import-only fields
        KeycloakUserCr cr = new KeycloakUserCr();
        cr.setMetadata(new ObjectMetaBuilder().withName("alice").withNamespace("test").build());
        cr.setSpec(spec);
        String wireJson = K8sStorageBackend.buildSerialization().asJson(cr);
        assertTrue(wireJson.contains("engineering"), wireJson);
        assertFalse(wireJson.contains("absent"), wireJson);
        assertFalse(wireJson.contains(":null"), wireJson);
        assertFalse(wireJson.contains("rawAttributes"), wireJson);
        assertFalse(wireJson.contains("disableableCredentialTypes"), wireJson);
        assertTrue(wireJson.contains("hashIterations"), wireJson);
    }

    @Test
    void userSpecExcludedPropertiesDoNotSerializeAndUnknownPropertiesAreIgnored() throws Exception {
        // excluded runtime/import-only properties never serialize
        UserSpec spec = new UserSpec();
        spec.setId("bob");
        spec.setRealm("master");
        spec.setUsername("bob");
        spec.setSelf("/admin/users/bob");
        spec.setOrigin("import");
        spec.setTotp(true);
        spec.setAccess(Map.of("manage", true));
        String json = K8sStorageBackend.configureMapper(new ObjectMapper()).writeValueAsString(spec);
        assertFalse(json.contains("self"), json);
        assertFalse(json.contains("origin"), json);
        assertFalse(json.contains("totp"), json);
        assertFalse(json.contains("access"), json);
        assertTrue(json.contains("bob"), json);

        // rolling-upgrade tolerance: unknown properties from newer schema generations are ignored
        UserSpec parsed = K8sStorageBackend.configureMapper(new ObjectMapper())
                .readValue("{\"username\":\"bob\",\"someFutureField\":42}", UserSpec.class);
        assertEquals("bob", parsed.getUsername());
    }

    @Test
    void consentEntriesRoundTripWithScopeParametersUnderTheStandardJsonName() throws Exception {
        // write side: the typed consent property serializes as the standard "clientConsents"
        // and carries the parameterized-scopes parameters
        UserSpec spec = new UserSpec();
        spec.setId("carol");
        spec.setRealm("master");
        spec.setUsername("carol");
        UserConsentSpec consent = new UserConsentSpec();
        consent.setClientId("wallet");
        consent.setGrantedClientScopes(new ArrayList<>(List.of("profile", "tenant")));
        consent.setGrantedScopeParameters(new HashMap<>(Map.of("tenant", List.of("acme", "globex"))));
        consent.setCreatedDate(1000L);
        spec.setConsents(new ArrayList<>(List.of(consent)));

        String json = K8sStorageBackend.configureMapper(new ObjectMapper()).writeValueAsString(spec);
        assertTrue(json.contains("\"clientConsents\""), json);
        assertFalse(json.contains("\"consents\""), json);
        assertTrue(json.contains("grantedScopeParameters"), json);
        assertTrue(json.contains("globex"), json);

        // read side: authored/mirrored JSON binds into the typed property, parameters included
        UserSpec parsed = K8sStorageBackend.configureMapper(new ObjectMapper()).readValue(json, UserSpec.class);
        assertEquals(1, parsed.getConsents().size());
        assertEquals(List.of("acme", "globex"),
                parsed.getConsents().get(0).getGrantedScopeParameters().get("tenant"));
        assertEquals(List.of("profile", "tenant"), parsed.getConsents().get(0).getGrantedClientScopes());

        // and through the backend mirror (defensive copies use the same mapper rules)
        start(true, EnumSet.allOf(Area.class));
        K8sStorageBackend.update(UserSpec.class, "master", "carol", spec);
        UserSpec read = K8sStorageBackend.get().read(UserSpec.class, "master", "carol");
        assertEquals(List.of("acme", "globex"),
                read.getConsents().get(0).getGrantedScopeParameters().get("tenant"));
    }

    @Test
    void handAuthoredUserCrDefaultsIdFromMetadataAndRealmFromLabel() {
        KeycloakUserCr cr = new KeycloakUserCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("hand-authored-user")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        UserSpec spec = new UserSpec();
        spec.setUsername("hand-authored-user");
        cr.setSpec(spec);
        client.resource(cr).create();

        K8sStorageBackend backend = start(true, EnumSet.allOf(Area.class));

        UserSpec read = backend.read(UserSpec.class, "master", "hand-authored-user");
        assertNotNull(read, "spec.id must default to metadata.name, the realm to the realm label");
        assertEquals("hand-authored-user", read.getUsername());
    }

    // ------------------------------------------------------------------ single-use primitives

    @Test
    void createNowAndDeleteNowAreExactlyOnce() {
        start(false, EnumSet.allOf(Area.class));
        String realm = K8sStorageBackend.GLOBAL_PSEUDO_REALM;
        SingleUseObjectSpec spec = new SingleUseObjectSpec();
        spec.setKey("token-key");
        spec.setNotes(Map.of("note", "value"));
        spec.setExpiresAt(Time.currentTimeMillis() + 60_000);

        assertTrue(K8sStorageBackend.createNow(SingleUseObjectSpec.class, realm, "token-key", spec));
        assertFalse(K8sStorageBackend.createNow(SingleUseObjectSpec.class, realm, "token-key", spec),
                "the second creator must lose the atomic create");
        assertNotNull(K8sStorageBackend.get().fetch(SingleUseObjectSpec.class, realm, "token-key"));

        assertTrue(K8sStorageBackend.deleteNow(SingleUseObjectSpec.class, realm, "token-key"));
        assertFalse(K8sStorageBackend.deleteNow(SingleUseObjectSpec.class, realm, "token-key"),
                "the second deleter must observe that the object is gone");
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for condition");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
