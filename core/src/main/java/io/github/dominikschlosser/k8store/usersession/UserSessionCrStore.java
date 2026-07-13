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
package io.github.dominikschlosser.k8store.usersession;

import io.github.dominikschlosser.k8store.common.CrStore;
import io.github.dominikschlosser.k8store.crd.UserSessionSpec;
import java.util.List;

/**
 * Access to {@code KeycloakUserSession} custom resources, keyed by {@code (realm, session id)}.
 * Reads come from the informer mirror (expired sessions are filtered there) and hand out
 * defensive copies; every mutation of a spec must be persisted explicitly through {@link #save}.
 * Writes go through the per-transaction buffer like the config kinds - the many setter calls of
 * one login coalesce into one server-side apply at commit.
 */
public final class UserSessionCrStore {

    private static final CrStore<UserSessionSpec> STORE =
            new CrStore<>(UserSessionSpec.class, UserSessionSpec::getRealm, UserSessionSpec::getId);

    private UserSessionCrStore() {}

    public static UserSessionSpec read(String realmId, String id) {
        return STORE.read(realmId, id);
    }

    public static List<UserSessionSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static UserSessionSpec save(UserSessionSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String id) {
        STORE.delete(realmId, id);
    }
}
