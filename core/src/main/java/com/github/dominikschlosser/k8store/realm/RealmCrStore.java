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
package com.github.dominikschlosser.k8store.realm;

import com.github.dominikschlosser.k8store.common.CrStore;
import com.github.dominikschlosser.k8store.crd.RealmSpec;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Access to {@code KeycloakRealm} custom resources. The realm name is the store id (both live in
 * {@code spec.realm}). Reads come from the informer mirror and hand out defensive copies; every
 * mutation of a spec must be persisted explicitly through {@link #save} - specs carry no
 * write-through machinery.
 *
 * <p>Embedded per-kind collections ({@code clients}, {@code roles}, ...) in a realm spec are not
 * served - per-kind CRs are the storage. The CRD schema already prunes them on the API server;
 * content that arrives anyway (e.g. through a mock server or direct population) is reported once
 * per realm with a warning listing what was ignored.
 */
public final class RealmCrStore {

    private static final Logger LOG = Logger.getLogger(RealmCrStore.class);

    // the realm name is both the realm id and the store id (both live in spec.realm).
    private static final CrStore<RealmSpec> STORE =
            new CrStore<>(RealmSpec.class, RealmSpec::getRealm, RealmSpec::getRealm);

    /** Realms already warned about embedded collections, to avoid log spam on hot-path reads. */
    private static final Set<String> WARNED_REALMS = ConcurrentHashMap.newKeySet();

    private RealmCrStore() {}

    public static RealmSpec read(String realmId) {
        return checked(STORE.read(realmId, realmId));
    }

    public static boolean exists(String realmId) {
        return realmId != null && STORE.exists(realmId, realmId);
    }

    public static List<RealmSpec> readAll() {
        List<RealmSpec> all = STORE.readAll();
        all.forEach(RealmCrStore::checked);
        return all;
    }

    public static RealmSpec save(RealmSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId) {
        if (realmId != null) {
            STORE.delete(realmId, realmId);
            WARNED_REALMS.remove(realmId);
        }
    }

    private static RealmSpec checked(RealmSpec spec) {
        if (spec == null) {
            return null;
        }
        List<String> ignored = spec.ignoredEmbeddedCollections();
        if (!ignored.isEmpty() && WARNED_REALMS.add(spec.getRealm())) {
            LOG.warnv(
                    "KeycloakRealm CR for realm {0} embeds the collections {1}; they are ignored -"
                            + " per-kind custom resources (KeycloakClient, KeycloakRole, KeycloakGroup,"
                            + " KeycloakClientScope) are the storage for those entities",
                    spec.getRealm(), String.join(", ", ignored));
        }
        return spec;
    }
}
