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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import io.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Realm configuration depth: lifespans, policies, brute-force settings, localization, required
 * actions and authentication flows must round-trip through the admin API exactly like the default
 * storage, with every value visible in the KeycloakRealm custom resource spec.
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class RealmConfigStorageTest {

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

    @Test
    public void tokenLifespanSettingsRoundTripThroughRealmCustomResource() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        rep.setAccessTokenLifespan(1234);
        rep.setSsoSessionIdleTimeout(2345);
        rep.setSsoSessionMaxLifespan(34567);
        rep.setAccessCodeLifespanLogin(456);
        realm.admin().update(rep);

        RealmRepresentation readBack = realm.admin().toRepresentation();
        assertEquals(1234, readBack.getAccessTokenLifespan());
        assertEquals(2345, readBack.getSsoSessionIdleTimeout());
        assertEquals(34567, readBack.getSsoSessionMaxLifespan());
        assertEquals(456, readBack.getAccessCodeLifespanLogin());

        KeycloakRealmCr cr = realmCr();
        assertEquals(1234, cr.getSpec().getAccessTokenLifespan());
        assertEquals(2345, cr.getSpec().getSsoSessionIdleTimeout());
        assertEquals(34567, cr.getSpec().getSsoSessionMaxLifespan());
        assertEquals(456, cr.getSpec().getAccessCodeLifespanLogin());
    }

    @Test
    public void passwordPolicyRoundTripsThroughRealmCustomResource() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        rep.setPasswordPolicy("length(12) and digits(2)");
        realm.admin().update(rep);

        assertEquals(
                "length(12) and digits(2)", realm.admin().toRepresentation().getPasswordPolicy());
        assertEquals(
                "length(12) and digits(2)",
                realmCr().getSpec().getPasswordPolicy(),
                "password policy must be stored in the realm CR");
    }

    @Test
    public void bruteForceSettingsRoundTripThroughRealmCustomResource() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        rep.setBruteForceProtected(true);
        rep.setFailureFactor(7);
        rep.setWaitIncrementSeconds(90);
        realm.admin().update(rep);

        RealmRepresentation readBack = realm.admin().toRepresentation();
        assertTrue(readBack.isBruteForceProtected());
        assertEquals(7, readBack.getFailureFactor());
        assertEquals(90, readBack.getWaitIncrementSeconds());

        KeycloakRealmCr cr = realmCr();
        assertEquals(
                Boolean.TRUE,
                cr.getSpec().isBruteForceProtected(),
                "brute-force settings must be stored as first-class representation fields");
        assertEquals(7, cr.getSpec().getFailureFactor());
        assertEquals(90, cr.getSpec().getWaitIncrementSeconds());
    }

    @Test
    public void localizationTextsRoundTripThroughRealmCustomResource() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        rep.setInternationalizationEnabled(true);
        rep.setSupportedLocales(Set.of("en", "de"));
        realm.admin().update(rep);

        realm.admin()
                .localization()
                .createOrUpdateRealmLocalizationTexts("de", Map.of("welcome", "Willkommen", "logout", "Abmelden"));

        Map<String, String> readBack = realm.admin().localization().getRealmLocalizationTexts("de");
        assertEquals("Willkommen", readBack.get("welcome"));
        assertEquals("Abmelden", readBack.get("logout"));

        KeycloakRealmCr cr = realmCr();
        assertTrue(cr.getSpec().isInternationalizationEnabled());
        assertTrue(cr.getSpec().getSupportedLocales().contains("de"));
        Map<String, String> crTexts = cr.getSpec().getLocalizationTexts().get("de");
        assertNotNull(crTexts, "localization texts must be embedded in the realm CR");
        assertEquals("Willkommen", crTexts.get("welcome"));
        assertEquals("Abmelden", crTexts.get("logout"));
    }

    @Test
    public void requiredActionToggleRoundTripsThroughRealmCustomResource() {
        assertFalse(
                realm.admin().flows().getRequiredActions().isEmpty(),
                "a freshly created realm must expose its registered required actions");
        assertFalse(
                realmCr().getSpec().getRequiredActions().isEmpty(),
                "required action providers must be embedded in the realm CR");

        RequiredActionProviderRepresentation totp = realm.admin().flows().getRequiredAction("CONFIGURE_TOTP");
        boolean toggled = !totp.isEnabled();
        totp.setEnabled(toggled);
        realm.admin().flows().updateRequiredAction("CONFIGURE_TOTP", totp);

        assertEquals(
                toggled,
                realm.admin().flows().getRequiredAction("CONFIGURE_TOTP").isEnabled());
        assertEquals(
                toggled,
                realmCr().getSpec().getRequiredActions().stream()
                        .filter(a -> "CONFIGURE_TOTP".equals(a.getAlias()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("CONFIGURE_TOTP missing from realm CR"))
                        .isEnabled(),
                "required action toggle must be reflected in the realm CR");
    }

    @Test
    public void authenticationFlowsFromBootArePresentInRealmCustomResource() {
        assertTrue(
                realm.admin().flows().getFlows().stream().anyMatch(f -> "browser".equals(f.getAlias())),
                "built-in browser flow must exist");

        KeycloakRealmCr cr = realmCr();
        assertFalse(
                cr.getSpec().getAuthenticationFlows().isEmpty(),
                "authentication flows must be embedded in the realm CR");
        assertTrue(
                cr.getSpec().getAuthenticationFlows().stream().anyMatch(f -> "browser".equals(f.getAlias())),
                "browser flow must be embedded in the realm CR");
        assertNotNull(cr.getSpec().getBrowserFlow(), "flow bindings must be stored in the realm CR");
        assertFalse(
                cr.getSpec().getAuthenticationFlows().stream()
                        .filter(f -> "browser".equals(f.getAlias()))
                        .findFirst()
                        .orElseThrow()
                        .getAuthenticationExecutions()
                        .isEmpty(),
                "flow executions must be embedded in their flow in the realm CR");
    }

    @Test
    public void displayNameAndLoginSettingsRoundTripThroughRealmCustomResource() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        rep.setDisplayName("K8store Test Realm");
        rep.setLoginWithEmailAllowed(false);
        rep.setRememberMe(true);
        realm.admin().update(rep);

        RealmRepresentation readBack = realm.admin().toRepresentation();
        assertEquals("K8store Test Realm", readBack.getDisplayName());
        assertFalse(readBack.isLoginWithEmailAllowed());
        assertTrue(readBack.isRememberMe());

        KeycloakRealmCr cr = realmCr();
        assertEquals("K8store Test Realm", cr.getSpec().getDisplayName());
        assertFalse(cr.getSpec().isLoginWithEmailAllowed());
        assertTrue(cr.getSpec().isRememberMe());
    }
}
