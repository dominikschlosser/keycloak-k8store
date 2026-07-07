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
package com.github.dominikschlosser.k8store.client;

import com.github.dominikschlosser.k8store.crd.ClientSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;

/**
 * Access to {@code KeycloakClient} custom resources, keyed by {@code (realm, clientId)} - the
 * clientId is the store id in this store. Reads come from the informer mirror and hand out
 * defensive copies; every mutation of a spec must be persisted explicitly through {@link #save}
 * - specs carry no write-through machinery.
 */
public final class ClientCrStore {

    private ClientCrStore() {}

    public static ClientSpec read(String realmId, String clientId) {
        return K8sStorageBackend.get().read(ClientSpec.class, realmId, clientId);
    }

    public static boolean exists(String realmId, String clientId) {
        return K8sStorageBackend.get().exists(ClientSpec.class, realmId, clientId);
    }

    public static List<ClientSpec> allInRealm(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(ClientSpec.class, realmId);
    }

    public static ClientSpec save(ClientSpec spec) {
        return K8sStorageBackend.update(ClientSpec.class, spec.getRealm(), spec.getClientId(), spec);
    }

    public static void delete(String realmId, String clientId) {
        if (realmId != null && clientId != null) {
            K8sStorageBackend.delete(ClientSpec.class, realmId, clientId);
        }
    }
}
