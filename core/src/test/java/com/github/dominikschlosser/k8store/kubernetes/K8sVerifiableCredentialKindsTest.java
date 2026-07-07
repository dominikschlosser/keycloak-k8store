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

import com.github.dominikschlosser.k8store.crd.IssuedVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.crd.UserVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakIssuedVerifiableCredentialCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserVerifiableCredentialCr;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.Profile;
import org.keycloak.common.util.Time;

/**
 * The OID4VC verifiable-credential kinds of the {@code user} area: registration is gated on the
 * user area AND the (experimental) {@code oid4vc-vci} feature, both kinds stay writable in
 * read-only mode (runtime data), specs round-trip null-free, and issued credentials are wired
 * into the expiry handling (read filtering + reaper).
 */
@EnableKubernetesMockClient(crud = true)
class K8sVerifiableCredentialKindsTest {

    KubernetesClient client;

    @AfterEach
    void shutdownBackend() {
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();
        Profile.reset();
    }

    private K8sStorageBackend start(boolean readOnly, Set<Area> areas) {
        return K8sStorageBackend.initWithClient(client, K8sStoreConfig.of(readOnly, areas, "test", false, 30));
    }

    private static void enableOid4vc(boolean enabled) {
        Profile.init(Profile.ProfileName.DEFAULT, Map.of(Profile.Feature.OID4VC_VCI, enabled));
    }

    // ------------------------------------------------------------------ registration gating

    @Test
    void verifiableCredentialKindsRequireTheUserAreaAndTheFeature() {
        enableOid4vc(false);
        K8sStorageBackend withoutFeature = start(false, EnumSet.allOf(Area.class));
        assertThrows(IllegalArgumentException.class,
                () -> withoutFeature.read(UserVerifiableCredentialSpec.class, "master", "vc-1"),
                "without the oid4vc-vci feature the kinds must not be registered (no informers, no CRDs)");
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();

        enableOid4vc(true);
        K8sStorageBackend withoutUserArea = start(false, K8sStoreConfig.configAreas());
        assertThrows(IllegalArgumentException.class,
                () -> withoutUserArea.read(UserVerifiableCredentialSpec.class, "master", "vc-1"),
                "the kinds belong to the user area — config-only areas must not register them");
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();

        K8sStorageBackend withBoth = start(false, EnumSet.allOf(Area.class));
        assertNull(withBoth.read(UserVerifiableCredentialSpec.class, "master", "vc-1"),
                "with user area + feature the kind is registered (and empty)");
        assertNull(withBoth.read(IssuedVerifiableCredentialSpec.class, "master", "ivc-1"));
    }

    // ------------------------------------------------------------------ writability + round trip

    @Test
    void verifiableCredentialSpecsAreWritableInReadOnlyModeAndRoundTripNullFree() {
        enableOid4vc(true);
        start(true, EnumSet.allOf(Area.class)); // read-only: VC kinds are runtime data

        UserVerifiableCredentialSpec vc = new UserVerifiableCredentialSpec();
        vc.setId("vc-1");
        vc.setRealm("master");
        vc.setUserId("alice");
        vc.setClientScopeId("university-degree");
        vc.setRevision("rev-1");
        vc.setCreatedDate(1000L);
        vc.setUpdatedDate(2000L);
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("degree", List.of("msc"));
        attributes.put("absent", null);
        vc.setUserAttributes(attributes);
        K8sStorageBackend.update(UserVerifiableCredentialSpec.class, "master", "vc-1", vc);

        UserVerifiableCredentialSpec read =
                K8sStorageBackend.get().read(UserVerifiableCredentialSpec.class, "master", "vc-1");
        assertNotNull(read, "runtime kinds must accept writes in read-only mode");
        assertEquals("university-degree", read.getClientScopeId());
        assertEquals("rev-1", read.getRevision());
        assertEquals(List.of("msc"), read.getUserAttributes().get("degree"));

        KeycloakUserVerifiableCredentialCr cr = new KeycloakUserVerifiableCredentialCr();
        cr.setMetadata(new ObjectMetaBuilder().withName("vc-1").withNamespace("test").build());
        cr.setSpec(vc);
        String wireJson = K8sStorageBackend.buildSerialization().asJson(cr);
        assertTrue(wireJson.contains("university-degree"), wireJson);
        assertFalse(wireJson.contains("absent"), wireJson);
        assertFalse(wireJson.contains(":null"), wireJson);
        assertEquals(1, client.resources(KeycloakUserVerifiableCredentialCr.class)
                .inNamespace("test").list().getItems().size());
    }

    // ------------------------------------------------------------------ issued-credential expiry

    private KeycloakIssuedVerifiableCredentialCr issuedCr(String id, Long expiresAt) {
        IssuedVerifiableCredentialSpec spec = new IssuedVerifiableCredentialSpec();
        spec.setId(id);
        spec.setRealm("master");
        spec.setUserId("alice");
        spec.setVerifiableCredentialId("vc-1");
        spec.setClientId("wallet");
        spec.setIssuedAt(Time.currentTimeMillis());
        spec.setExpiresAt(expiresAt);
        KeycloakIssuedVerifiableCredentialCr cr = new KeycloakIssuedVerifiableCredentialCr();
        cr.setMetadata(new ObjectMetaBuilder().withName(id).withNamespace("test").build());
        cr.setSpec(spec);
        return cr;
    }

    @Test
    void expiredIssuedCredentialsAreFilteredAndReaped() {
        enableOid4vc(true);
        K8sStorageBackend backend = start(false, EnumSet.allOf(Area.class));
        long now = Time.currentTimeMillis();
        client.resource(issuedCr("expired", now - 1000)).create();
        client.resource(issuedCr("live", now + 60_000)).create();
        client.resource(issuedCr("forever", null)).create();

        await(() -> backend.read(IssuedVerifiableCredentialSpec.class, "master", "live") != null);
        await(() -> backend.read(IssuedVerifiableCredentialSpec.class, "master", "forever") != null);
        assertNull(backend.read(IssuedVerifiableCredentialSpec.class, "master", "expired"),
                "expired issuances must never be handed to the model layer");
        assertEquals(2, backend.readAllInRealm(IssuedVerifiableCredentialSpec.class, "master").size());

        backend.sweepExpired();
        var remaining = client.resources(KeycloakIssuedVerifiableCredentialCr.class)
                .inNamespace("test").list().getItems();
        assertEquals(2, remaining.size(), "the reaper must delete expired issued-credential CRs only");
        assertTrue(remaining.stream().noneMatch(cr -> "expired".equals(cr.getSpec().getId())));
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
