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
package com.github.dominikschlosser.k8store.loginfailure;

import com.github.dominikschlosser.k8store.common.CrStore;
import com.github.dominikschlosser.k8store.crd.LoginFailureSpec;
import java.util.List;

/**
 * Access to {@code KeycloakLoginFailure} custom resources, keyed by {@code (realm, user id)} -
 * the user id is the store id, one CR per (realm, user). Reads come from the informer mirror;
 * every mutation must be persisted explicitly through {@link #save} (transaction-buffered, so
 * the several counter updates of one failed login coalesce into one apply).
 */
public final class LoginFailureCrStore {

    private static final CrStore<LoginFailureSpec> STORE =
            new CrStore<>(LoginFailureSpec.class, LoginFailureSpec::getRealm, LoginFailureSpec::getUserId);

    private LoginFailureCrStore() {}

    public static LoginFailureSpec read(String realmId, String userId) {
        return STORE.read(realmId, userId);
    }

    public static List<LoginFailureSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static LoginFailureSpec save(LoginFailureSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String userId) {
        STORE.delete(realmId, userId);
    }
}
