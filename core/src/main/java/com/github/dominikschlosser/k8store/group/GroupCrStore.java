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
package com.github.dominikschlosser.k8store.group;

import com.github.dominikschlosser.k8store.crd.GroupSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;

/**
 * Access to {@code KeycloakGroup} custom resources, keyed by {@code (realm, id)}. Reads come
 * from the informer mirror and hand out defensive copies; every mutation of a spec must be
 * persisted explicitly through {@link #save} — specs carry no write-through machinery.
 */
public final class GroupCrStore {

    private GroupCrStore() {}

    public static GroupSpec read(String realmId, String id) {
        return K8sStorageBackend.get().read(GroupSpec.class, realmId, id);
    }

    public static boolean exists(String realmId, String id) {
        return K8sStorageBackend.get().exists(GroupSpec.class, realmId, id);
    }

    public static List<GroupSpec> allInRealm(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(GroupSpec.class, realmId);
    }

    public static GroupSpec save(GroupSpec spec) {
        return K8sStorageBackend.update(GroupSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    public static void delete(String realmId, String id) {
        if (realmId != null && id != null) {
            K8sStorageBackend.delete(GroupSpec.class, realmId, id);
        }
    }
}
