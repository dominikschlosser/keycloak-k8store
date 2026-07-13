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
package io.github.dominikschlosser.k8store.common;

import io.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;
import java.util.function.Function;

/**
 * Generic access to one custom-resource spec type over {@link K8sStorageBackend}. Reads come from
 * the informer mirror and hand out defensive copies; every mutation of a spec must be persisted
 * explicitly through {@link #save} - specs carry no write-through machinery.
 *
 * <p>The per-area {@code *CrStore} classes each bind a spec type and its {@code realm}/{@code id}
 * accessors to one instance of this helper and delegate their static entry points to it, so the
 * read/exists/list/save/delete boilerplate lives in exactly one place while the call sites keep
 * their readable, area-specific names.
 *
 * @param <S> the custom-resource spec type
 */
public final class CrStore<S> {

    private final Class<S> specClass;
    private final Function<S, String> realmFn;
    private final Function<S, String> idFn;

    /**
     * @param specClass the spec type indexed by the backend
     * @param realmFn   the spec's realm id (the write path derives the key from the spec)
     * @param idFn      the spec's store id within its realm
     */
    public CrStore(Class<S> specClass, Function<S, String> realmFn, Function<S, String> idFn) {
        this.specClass = specClass;
        this.realmFn = realmFn;
        this.idFn = idFn;
    }

    public S read(String realmId, String id) {
        return K8sStorageBackend.get().read(specClass, realmId, id);
    }

    public boolean exists(String realmId, String id) {
        return K8sStorageBackend.get().exists(specClass, realmId, id);
    }

    public List<S> allInRealm(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(specClass, realmId);
    }

    public List<S> readAll() {
        return K8sStorageBackend.get().readAll(specClass);
    }

    public S save(S spec) {
        return K8sStorageBackend.update(specClass, realmFn.apply(spec), idFn.apply(spec), spec);
    }

    public void delete(String realmId, String id) {
        if (realmId != null && id != null) {
            K8sStorageBackend.delete(specClass, realmId, id);
        }
    }
}
