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
package io.github.dominikschlosser.k8store.authsession;

import io.github.dominikschlosser.k8store.common.CrStore;
import io.github.dominikschlosser.k8store.crd.AuthSessionSpec;
import java.util.List;

/**
 * Access to {@code KeycloakAuthSession} custom resources (root authentication sessions, tabs
 * embedded), keyed by {@code (realm, root session id)}. Reads come from the informer mirror
 * (expired sessions are filtered there) and hand out defensive copies; every mutation must be
 * persisted explicitly through {@link #save}, which goes through the per-transaction buffer.
 */
public final class AuthSessionCrStore {

    private static final CrStore<AuthSessionSpec> STORE =
            new CrStore<>(AuthSessionSpec.class, AuthSessionSpec::getRealm, AuthSessionSpec::getId);

    private AuthSessionCrStore() {}

    public static AuthSessionSpec read(String realmId, String id) {
        return STORE.read(realmId, id);
    }

    public static List<AuthSessionSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static AuthSessionSpec save(AuthSessionSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String id) {
        STORE.delete(realmId, id);
    }
}
