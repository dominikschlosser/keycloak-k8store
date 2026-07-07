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
package com.github.dominikschlosser.k8store.clientscope;

import com.github.dominikschlosser.k8store.crd.ClientScopeSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;

/**
 * Access to {@code KeycloakClientScope} custom resources, keyed by {@code (realm, name)} - the
 * scope name is the store id in this store. Reads come from the informer mirror and hand out
 * defensive copies; every mutation of a spec must be persisted explicitly through {@link #save}
 * - specs carry no write-through machinery.
 */
public final class ClientScopeCrStore {

    private ClientScopeCrStore() {}

    public static ClientScopeSpec read(String realmId, String name) {
        return K8sStorageBackend.get().read(ClientScopeSpec.class, realmId, name);
    }

    public static boolean exists(String realmId, String name) {
        return K8sStorageBackend.get().exists(ClientScopeSpec.class, realmId, name);
    }

    public static List<ClientScopeSpec> allInRealm(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(ClientScopeSpec.class, realmId);
    }

    public static ClientScopeSpec save(ClientScopeSpec spec) {
        return K8sStorageBackend.update(ClientScopeSpec.class, spec.getRealm(), spec.getName(), spec);
    }

    public static void delete(String realmId, String name) {
        if (realmId != null && name != null) {
            K8sStorageBackend.delete(ClientScopeSpec.class, realmId, name);
        }
    }
}
