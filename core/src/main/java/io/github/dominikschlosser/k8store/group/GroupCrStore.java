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
package io.github.dominikschlosser.k8store.group;

import io.github.dominikschlosser.k8store.common.CrStore;
import io.github.dominikschlosser.k8store.crd.GroupSpec;
import java.util.List;

/**
 * Access to {@code KeycloakGroup} custom resources, keyed by {@code (realm, id)}. Reads come
 * from the informer mirror and hand out defensive copies; every mutation of a spec must be
 * persisted explicitly through {@link #save} - specs carry no write-through machinery.
 */
public final class GroupCrStore {

    private static final CrStore<GroupSpec> STORE =
            new CrStore<>(GroupSpec.class, GroupSpec::getRealm, GroupSpec::getId);

    private GroupCrStore() {}

    public static GroupSpec read(String realmId, String id) {
        return STORE.read(realmId, id);
    }

    public static boolean exists(String realmId, String id) {
        return STORE.exists(realmId, id);
    }

    public static List<GroupSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static GroupSpec save(GroupSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String id) {
        STORE.delete(realmId, id);
    }
}
