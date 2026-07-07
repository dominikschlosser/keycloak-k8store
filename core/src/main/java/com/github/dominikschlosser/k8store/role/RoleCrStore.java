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
package com.github.dominikschlosser.k8store.role;

import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;

/**
 * Access to {@code KeycloakRole} custom resources, keyed by {@code (realm, id)}. Reads come from
 * the informer mirror and hand out defensive copies; every mutation of a spec must be persisted
 * explicitly through {@link #save} - specs carry no write-through machinery.
 */
public final class RoleCrStore {

    private RoleCrStore() {}

    public static RoleSpec read(String realmId, String id) {
        return K8sStorageBackend.get().read(RoleSpec.class, realmId, id);
    }

    public static boolean exists(String realmId, String id) {
        return K8sStorageBackend.get().exists(RoleSpec.class, realmId, id);
    }

    public static List<RoleSpec> allInRealm(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(RoleSpec.class, realmId);
    }

    public static RoleSpec save(RoleSpec spec) {
        return K8sStorageBackend.update(RoleSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    public static void delete(String realmId, String id) {
        if (realmId != null && id != null) {
            K8sStorageBackend.delete(RoleSpec.class, realmId, id);
        }
    }
}
