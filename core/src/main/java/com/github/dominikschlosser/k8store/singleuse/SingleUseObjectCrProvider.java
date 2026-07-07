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
package com.github.dominikschlosser.k8store.singleuse;

import com.github.dominikschlosser.k8store.crd.SingleUseObjectSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.HashMap;
import java.util.Map;
import org.keycloak.common.util.Time;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * {@link SingleUseObjectProvider} over {@code KeycloakSingleUseObject} custom resources, indexed
 * under the {@linkplain K8sStorageBackend#GLOBAL_PSEUDO_REALM global pseudo-realm} (single-use
 * objects are not realm-scoped).
 *
 * <p>Unlike the session kinds, every operation goes straight to the API server (no transaction
 * buffering): action tokens, nonces and replay guards must be visible to the next request — on
 * any node — the moment the call returns, and the single-use guarantee of {@link #remove} is
 * exactly Kubernetes DELETE semantics (the object is handed to precisely one deleter;
 * {@link #putIfAbsent} maps onto atomic create the same way). Single-use-critical reads fall
 * back to a direct API-server GET on a mirror miss so a value written on another node
 * milliseconds ago is found; only {@link #contains} stays mirror-only (hot path).
 */
public class SingleUseObjectCrProvider implements SingleUseObjectProvider {

    private static SingleUseObjectSpec fetch(String key) {
        return K8sStorageBackend.get().fetch(
                SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key);
    }

    private static SingleUseObjectSpec newSpec(String key, long lifespanSeconds, Map<String, String> notes) {
        SingleUseObjectSpec spec = new SingleUseObjectSpec();
        spec.setKey(key);
        spec.setNotes(notes == null || notes.isEmpty() ? null : new HashMap<>(notes));
        spec.setExpiresAt(Time.currentTimeMillis() + lifespanSeconds * 1000L);
        return spec;
    }

    private static Map<String, String> notesOf(SingleUseObjectSpec spec) {
        return spec.getNotes() == null ? new HashMap<>() : new HashMap<>(spec.getNotes());
    }

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
        if (lifespanSeconds <= 0) {
            throw new IllegalArgumentException("lifespanSeconds must be positive");
        }
        K8sStorageBackend.updateNow(SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key,
                newSpec(key, lifespanSeconds, notes));
    }

    @Override
    public Map<String, String> get(String key) {
        SingleUseObjectSpec spec = fetch(key);
        return spec == null ? null : notesOf(spec);
    }

    @Override
    public Map<String, String> remove(String key) {
        SingleUseObjectSpec spec = fetch(key);
        if (spec == null) {
            return null;
        }
        // single-use contract: return the notes only if THIS call deleted the object —
        // Kubernetes answers a DELETE exactly once across all nodes
        boolean deleted = K8sStorageBackend.deleteNow(
                SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key);
        return deleted ? notesOf(spec) : null;
    }

    @Override
    public boolean replace(String key, Map<String, String> notes) {
        SingleUseObjectSpec spec = fetch(key);
        if (spec == null) {
            return false;
        }
        spec.setNotes(notes == null || notes.isEmpty() ? null : new HashMap<>(notes));
        K8sStorageBackend.updateNow(SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key, spec);
        return true;
    }

    @Override
    public boolean putIfAbsent(String key, long lifespanSeconds) {
        if (lifespanSeconds <= 0) {
            throw new IllegalArgumentException("lifespanSeconds must be positive");
        }
        SingleUseObjectSpec existing = fetch(key);
        if (existing != null) {
            return false;
        }
        SingleUseObjectSpec spec = newSpec(key, lifespanSeconds, null);
        if (K8sStorageBackend.createNow(SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key, spec)) {
            return true;
        }
        // name conflict: either a live entry won the race, or an expired CR is still awaiting
        // the reaper — an expired entry counts as absent, so claim it by overwrite
        if (fetch(key) != null) {
            return false;
        }
        K8sStorageBackend.updateNow(SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key, spec);
        return true;
    }

    @Override
    public boolean contains(String key) {
        // mirror-only on purpose: contains() sits on token-validation hot paths and a miss is
        // the common case — an API-server round trip per miss would bound request throughput.
        // Entries written on other nodes become visible within watch latency (milliseconds).
        return K8sStorageBackend.get().read(
                SingleUseObjectSpec.class, K8sStorageBackend.GLOBAL_PSEUDO_REALM, key) != null;
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
