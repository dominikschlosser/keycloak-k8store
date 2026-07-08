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
import com.github.dominikschlosser.k8store.crd.UserSpec;
import java.util.List;

/**
 * Access to {@code KeycloakUser} custom resources, keyed by {@code (realm, user id)} - the user
 * id is the lowercased username at creation, immutable afterwards. Reads come from the informer
 * mirror and hand out defensive copies; every mutation of a spec must be persisted explicitly
 * through {@link #save}. Writes go through the per-transaction buffer - the many setter calls of
 * one admin update or registration coalesce into one server-side apply at commit.
 */
public final class UserCrStore {

    private static final CrStore<UserSpec> STORE = new CrStore<>(UserSpec.class, UserSpec::getRealm, UserSpec::getId);

    private UserCrStore() {}

    public static UserSpec read(String realmId, String id) {
        return STORE.read(realmId, id);
    }

    public static boolean exists(String realmId, String id) {
        return STORE.exists(realmId, id);
    }

    public static List<UserSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static UserSpec save(UserSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String id) {
        STORE.delete(realmId, id);
    }
}
