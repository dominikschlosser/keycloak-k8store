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
package io.github.dominikschlosser.k8store.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dominikschlosser.k8store.crd.UserSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;

/**
 * The in-memory user search: case-insensitive matching against the lowercased-on-write
 * username/email fields, JPA-style term semantics (prefix by default, {@code *} wildcards,
 * quoted terms exact) and the service-account exclusion the admin console's user list relies on.
 */
class UserSearchTest {

    private UserSpec alice() {
        UserSpec spec = new UserSpec();
        spec.setId("alice");
        spec.setUsername("alice"); // usernames and emails are stored lowercased
        spec.setEmail("alice.smith@example.com");
        spec.setFirstName("Alice");
        spec.setLastName("Smith");
        spec.setEnabled(true);
        return spec;
    }

    @Test
    void searchTermsMatchCaseInsensitivelyAsPrefixes() {
        UserSpec alice = alice();
        assertTrue(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "ALICE")).test(alice),
                "mixed-case queries must find the lowercased stored username");
        assertTrue(UserSearch.predicate(Map.of(UserModel.SEARCH, "ali")).test(alice), "prefix match");
        assertTrue(UserSearch.predicate(Map.of(UserModel.SEARCH, "SMI")).test(alice), "last-name prefix");
        assertFalse(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "lice")).test(alice),
                "plain terms are prefixes, not infixes (JPA parity)");
        assertTrue(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "*lice")).test(alice),
                "* wildcards make it an infix match");
        assertTrue(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "\"Alice\"")).test(alice),
                "quoted terms match exactly, still case-insensitively");
        assertFalse(UserSearch.predicate(Map.of(UserModel.SEARCH, "\"Ali\"")).test(alice));
        assertTrue(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "alice smith")).test(alice),
                "every whitespace-separated term must match some field");
        assertFalse(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "alice jones")).test(alice));
    }

    @Test
    void fieldParametersMatchInfixOrExactCaseInsensitively() {
        UserSpec alice = alice();
        assertTrue(UserSearch.predicate(Map.of(UserModel.USERNAME, "LIC")).test(alice), "field parameters match infix");
        assertTrue(UserSearch.predicate(Map.of(UserModel.EMAIL, "Alice.Smith@Example.com"))
                .test(alice));
        assertTrue(UserSearch.predicate(Map.of(UserModel.USERNAME, "ALICE", UserModel.EXACT, "true"))
                .test(alice));
        assertFalse(UserSearch.predicate(Map.of(UserModel.USERNAME, "LIC", UserModel.EXACT, "true"))
                .test(alice));
        assertTrue(UserSearch.predicate(Map.of(UserModel.ENABLED, "true")).test(alice));
        assertFalse(UserSearch.predicate(Map.of(UserModel.ENABLED, "false")).test(alice));
    }

    @Test
    void serviceAccountsAreExcludedUnlessRequested() {
        UserSpec serviceAccount = alice();
        serviceAccount.setServiceAccountClientId("my-client");
        assertFalse(
                UserSearch.predicate(Map.of(UserModel.SEARCH, "alice")).test(serviceAccount),
                "the admin console's user list never shows service accounts by default");
        assertTrue(UserSearch.predicate(Map.of(UserModel.SEARCH, "alice", UserModel.INCLUDE_SERVICE_ACCOUNT, "true"))
                .test(serviceAccount));
    }

    @Test
    void unknownParameterKeysAreAttributeEqualityFilters() {
        UserSpec alice = alice();
        alice.setAttributes(Map.of("dept", List.of("engineering")));
        assertTrue(UserSearch.predicate(Map.of("dept", "engineering")).test(alice));
        assertFalse(UserSearch.predicate(Map.of("dept", "sales")).test(alice));
        assertFalse(UserSearch.predicate(Map.of("other", "engineering")).test(alice));
    }
}
