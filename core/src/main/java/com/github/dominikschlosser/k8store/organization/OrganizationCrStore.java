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
package com.github.dominikschlosser.k8store.organization;

import com.github.dominikschlosser.k8store.crd.OrganizationSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Access to {@code KeycloakOrganization} custom resources, keyed by {@code (realm, id)}. Reads
 * come from the informer mirror and hand out defensive copies; every mutation of a spec must be
 * persisted explicitly through {@link #save}.
 *
 * <p>Embedded collections in an organization spec ({@code members}, {@code groups},
 * {@code identityProviders}) are not served — membership lives on the users, organization
 * groups are {@code KeycloakGroup} CRs, the identity-provider linkage lives in the realm CR.
 * The CRD schema already prunes them on the API server; content that arrives anyway is
 * reported once per organization with a warning.
 */
public final class OrganizationCrStore {

    private static final Logger LOG = Logger.getLogger(OrganizationCrStore.class);

    /** Organizations already warned about embedded collections, to avoid log spam. */
    private static final Set<String> WARNED_ORGS = ConcurrentHashMap.newKeySet();

    private OrganizationCrStore() {}

    public static OrganizationSpec read(String realmId, String id) {
        return checked(K8sStorageBackend.get().read(OrganizationSpec.class, realmId, id));
    }

    public static boolean exists(String realmId, String id) {
        return realmId != null && id != null && K8sStorageBackend.get().exists(OrganizationSpec.class, realmId, id);
    }

    public static List<OrganizationSpec> allInRealm(String realmId) {
        List<OrganizationSpec> all = K8sStorageBackend.get().readAllInRealm(OrganizationSpec.class, realmId);
        all.forEach(OrganizationCrStore::checked);
        return all;
    }

    public static OrganizationSpec save(OrganizationSpec spec) {
        return K8sStorageBackend.update(OrganizationSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    public static void delete(String realmId, String id) {
        if (realmId != null && id != null) {
            K8sStorageBackend.delete(OrganizationSpec.class, realmId, id);
            WARNED_ORGS.remove(realmId + "/" + id);
        }
    }

    private static OrganizationSpec checked(OrganizationSpec spec) {
        if (spec == null) {
            return null;
        }
        List<String> ignored = spec.ignoredEmbeddedCollections();
        if (!ignored.isEmpty() && WARNED_ORGS.add(spec.getRealm() + "/" + spec.getId())) {
            LOG.warnv("KeycloakOrganization CR {0} (realm {1}) embeds the collections {2}; they are"
                            + " ignored — membership lives on the users, organization groups are"
                            + " KeycloakGroup CRs (spec.type: organization) and the identity-provider"
                            + " linkage is the organizationId field in the realm CR",
                    spec.getId(), spec.getRealm(), String.join(", ", ignored));
        }
        return spec;
    }
}
