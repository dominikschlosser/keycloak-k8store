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
package com.github.dominikschlosser.k8store.authsession;

import com.github.dominikschlosser.k8store.crd.AuthSessionSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;

/**
 * Access to {@code KeycloakAuthSession} custom resources (root authentication sessions, tabs
 * embedded), keyed by {@code (realm, root session id)}. Reads come from the informer mirror
 * (expired sessions are filtered there) and hand out defensive copies; every mutation must be
 * persisted explicitly through {@link #save}, which goes through the per-transaction buffer.
 */
public final class AuthSessionCrStore {

    private AuthSessionCrStore() {}

    public static AuthSessionSpec read(String realmId, String id) {
        return K8sStorageBackend.get().read(AuthSessionSpec.class, realmId, id);
    }

    public static List<AuthSessionSpec> allInRealm(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(AuthSessionSpec.class, realmId);
    }

    public static AuthSessionSpec save(AuthSessionSpec spec) {
        return K8sStorageBackend.update(AuthSessionSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    public static void delete(String realmId, String id) {
        if (realmId != null && id != null) {
            K8sStorageBackend.delete(AuthSessionSpec.class, realmId, id);
        }
    }
}
