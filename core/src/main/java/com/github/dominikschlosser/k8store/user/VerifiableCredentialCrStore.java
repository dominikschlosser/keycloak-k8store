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
package com.github.dominikschlosser.k8store.user;

import com.github.dominikschlosser.k8store.common.CrStore;
import com.github.dominikschlosser.k8store.crd.IssuedVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.crd.UserVerifiableCredentialSpec;
import java.util.List;

/**
 * Access to the two OID4VC custom-resource kinds of the {@code user} area -
 * {@code KeycloakUserVerifiableCredential} (a user's registered verifiable credentials) and
 * {@code KeycloakIssuedVerifiableCredential} (issuance events, expiring). Both kinds are only
 * registered when the {@code oid4vc-vci} feature is enabled together with the area; ids are
 * generated (upstream convention), realms scope the informer indexes.
 *
 * <p>Upstream's SPI is realm-blind on this surface (methods take user/credential ids only), so
 * by-id lookups without a realm scan all realms - unambiguous because the ids are generated.
 */
public final class VerifiableCredentialCrStore {

    private static final CrStore<UserVerifiableCredentialSpec> CREDENTIALS = new CrStore<>(
            UserVerifiableCredentialSpec.class,
            UserVerifiableCredentialSpec::getRealm, UserVerifiableCredentialSpec::getId);
    private static final CrStore<IssuedVerifiableCredentialSpec> ISSUED = new CrStore<>(
            IssuedVerifiableCredentialSpec.class,
            IssuedVerifiableCredentialSpec::getRealm, IssuedVerifiableCredentialSpec::getId);

    private VerifiableCredentialCrStore() {}

    // ------------------------------------------------------------------ user verifiable credentials

    public static UserVerifiableCredentialSpec readCredential(String realmId, String id) {
        return CREDENTIALS.read(realmId, id);
    }

    /** Cross-realm by-id lookup (the SPI passes no realm; generated ids are unambiguous). */
    public static UserVerifiableCredentialSpec findCredentialById(String id) {
        return CREDENTIALS.readAll().stream()
                .filter(spec -> id.equals(spec.getId()))
                .findFirst()
                .orElse(null);
    }

    public static List<UserVerifiableCredentialSpec> credentialsInRealm(String realmId) {
        return CREDENTIALS.allInRealm(realmId);
    }

    public static List<UserVerifiableCredentialSpec> allCredentials() {
        return CREDENTIALS.readAll();
    }

    public static UserVerifiableCredentialSpec saveCredential(UserVerifiableCredentialSpec spec) {
        return CREDENTIALS.save(spec);
    }

    public static void deleteCredential(String realmId, String id) {
        CREDENTIALS.delete(realmId, id);
    }

    // ------------------------------------------------------------------ issued verifiable credentials

    /** Cross-realm by-id lookup (the SPI passes no realm; generated ids are unambiguous). */
    public static IssuedVerifiableCredentialSpec findIssuedById(String id) {
        return ISSUED.readAll().stream()
                .filter(spec -> id.equals(spec.getId()))
                .findFirst()
                .orElse(null);
    }

    public static List<IssuedVerifiableCredentialSpec> issuedInRealm(String realmId) {
        return ISSUED.allInRealm(realmId);
    }

    public static List<IssuedVerifiableCredentialSpec> allIssued() {
        return ISSUED.readAll();
    }

    public static IssuedVerifiableCredentialSpec saveIssued(IssuedVerifiableCredentialSpec spec) {
        return ISSUED.save(spec);
    }

    public static void deleteIssued(String realmId, String id) {
        ISSUED.delete(realmId, id);
    }
}
