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
package io.github.dominikschlosser.k8store.tests.federation;

import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.UserStoragePrivateUtil;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.user.ImportedUserValidation;
import org.keycloak.storage.user.UserLookupProvider;

/**
 * Minimal test-only user-storage federation provider (LDAP stand-in) serving exactly one user.
 * It follows the standard import pattern: the first lookup creates a local shadow user through
 * {@code userLocalStorage()} - which is the k8store CR provider under test - and links it with
 * {@code setFederationLink}; password validation stays with this provider (the shadow user
 * stores no credentials), exercising the federated credential fan-out.
 *
 * <p>Lives in the tests module's <em>main</em> sources: the test framework's
 * {@code dependencyCurrentProject()} deploys {@code target/classes} (with its
 * {@code META-INF/services} entry) into the embedded server's providers - test-scope classes
 * are not deployed.
 */
public class TestFederationUserStorage implements UserStorageProviderFactory<TestFederationUserStorage.Provider> {

    public static final String PROVIDER_ID = "k8store-test-federation";
    public static final String USERNAME = "federated-import-user";
    public static final String PASSWORD = "federated-import-password";
    public static final String EMAIL = USERNAME + "@federation.example";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public Provider create(KeycloakSession session, ComponentModel model) {
        return new Provider(session, model);
    }

    public static class Provider
            implements UserStorageProvider, UserLookupProvider, CredentialInputValidator, ImportedUserValidation {

        private final KeycloakSession session;
        private final ComponentModel model;

        Provider(KeycloakSession session, ComponentModel model) {
            this.session = session;
            this.model = model;
        }

        @Override
        public UserModel getUserByUsername(RealmModel realm, String username) {
            if (!USERNAME.equalsIgnoreCase(username)) {
                return null;
            }
            UserModel local = UserStoragePrivateUtil.userLocalStorage(session).getUserByUsername(realm, USERNAME);
            if (local == null) {
                // the import path every import-style federation provider (LDAP) uses: create
                // the local shadow user and link it to this provider. No default required
                // actions - the federated account is ready to log in as imported.
                local = UserStoragePrivateUtil.userLocalStorage(session).addUser(realm, null, USERNAME, true, false);
                local.setEnabled(true);
                local.setEmail(EMAIL);
                local.setEmailVerified(true);
                // the default user profile requires first/last name - an incomplete profile
                // would force an UPDATE_PROFILE required action and break the direct grant
                local.setFirstName("Federated");
                local.setLastName("Import");
                local.setSingleAttribute("federated-origin", PROVIDER_ID);
                local.setFederationLink(model.getId());
            }
            return local;
        }

        @Override
        public UserModel getUserById(RealmModel realm, String id) {
            return null; // imported users are served by local storage
        }

        @Override
        public UserModel getUserByEmail(RealmModel realm, String email) {
            return EMAIL.equalsIgnoreCase(email) ? getUserByUsername(realm, USERNAME) : null;
        }

        @Override
        public UserModel validate(RealmModel realm, UserModel user) {
            return user; // every imported user of this provider stays valid
        }

        @Override
        public boolean supportsCredentialType(String credentialType) {
            return PasswordCredentialModel.TYPE.equals(credentialType);
        }

        @Override
        public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
            return supportsCredentialType(credentialType);
        }

        @Override
        public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
            return supportsCredentialType(input.getType()) && PASSWORD.equals(input.getChallengeResponse());
        }

        @Override
        public void close() {}
    }
}
