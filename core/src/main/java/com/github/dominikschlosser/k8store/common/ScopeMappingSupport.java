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
package com.github.dominikschlosser.k8store.common;

import com.github.dominikschlosser.k8store.crd.ScopeMappingCarrier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

/**
 * Shared {@link org.keycloak.models.ScopeContainerModel} logic of the client and client-scope
 * adapters, operating on the {@link ScopeMappingCarrier} fields of a spec. Mappings are stored
 * by role <em>name</em> - realm roles in {@code realmScopeMappings}, client roles in
 * {@code clientScopeMappings} keyed by the owning client's id (= clientId) - the same
 * representation-style convention phase 1 established for role composites. Every mutation runs
 * the supplied {@code persist} callback.
 */
public final class ScopeMappingSupport {

    private ScopeMappingSupport() {}

    public static Stream<RoleModel> stream(KeycloakSession session, RealmModel realm, ScopeMappingCarrier spec) {
        Stream<RoleModel> realmRoles = spec.getRealmScopeMappings() == null
                ? Stream.empty()
                : spec.getRealmScopeMappings().stream()
                        .map(name -> session.roles().getRealmRole(realm, name));
        Stream<RoleModel> clientRoles = spec.getClientScopeMappings() == null
                ? Stream.empty()
                : spec.getClientScopeMappings().entrySet().stream().flatMap(entry -> {
                    ClientModel client = resolveClient(session, realm, entry.getKey());
                    return client == null || entry.getValue() == null
                            ? Stream.empty()
                            : entry.getValue().stream()
                                    .map(name -> session.roles().getClientRole(client, name));
                });
        return Stream.concat(realmRoles, clientRoles).filter(Objects::nonNull);
    }

    public static Stream<RoleModel> realmStream(KeycloakSession session, RealmModel realm, ScopeMappingCarrier spec) {
        return spec.getRealmScopeMappings() == null
                ? Stream.empty()
                : spec.getRealmScopeMappings().stream()
                        .map(name -> session.roles().getRealmRole(realm, name))
                        .filter(Objects::nonNull);
    }

    public static void add(ScopeMappingCarrier spec, RoleModel role, Runnable persist) {
        if (role == null) {
            return;
        }
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientScopeMappings();
            if (byClient == null) {
                byClient = new HashMap<>();
                spec.setClientScopeMappings(byClient);
            }
            List<String> names = byClient.computeIfAbsent(role.getContainerId(), k -> new ArrayList<>());
            if (!names.contains(role.getName())) {
                names.add(role.getName());
                persist.run();
            }
        } else {
            List<String> names = spec.getRealmScopeMappings();
            if (names == null) {
                names = new ArrayList<>();
                spec.setRealmScopeMappings(names);
            }
            if (!names.contains(role.getName())) {
                names.add(role.getName());
                persist.run();
            }
        }
    }

    public static void delete(ScopeMappingCarrier spec, RoleModel role, Runnable persist) {
        if (role != null && removeRole(spec, role)) {
            persist.run();
        }
    }

    /** Whether {@code role} is directly mapped (no composite resolution, no role lookups). */
    public static boolean containsDirectly(ScopeMappingCarrier spec, RoleModel role) {
        if (role == null) {
            return false;
        }
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientScopeMappings();
            List<String> names = byClient == null ? null : byClient.get(role.getContainerId());
            return names != null && names.contains(role.getName());
        }
        return spec.getRealmScopeMappings() != null
                && spec.getRealmScopeMappings().contains(role.getName());
    }

    /**
     * Drops {@code removed} from the spec's mappings without persisting - cascade helper for
     * role removal; the caller persists when {@code true} is returned.
     */
    public static boolean removeRole(ScopeMappingCarrier spec, RoleModel removed) {
        if (removed.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientScopeMappings();
            List<String> names = byClient == null ? null : byClient.get(removed.getContainerId());
            if (names != null && names.remove(removed.getName())) {
                if (names.isEmpty()) {
                    byClient.remove(removed.getContainerId());
                }
                return true;
            }
            return false;
        }
        return spec.getRealmScopeMappings() != null
                && spec.getRealmScopeMappings().remove(removed.getName());
    }

    /**
     * Swaps {@code renamed}'s old name for {@code newName} in the spec's mappings without
     * persisting - cascade helper for role rename; the caller persists when {@code true} is
     * returned. The client-section list keeps its position on swap.
     */
    public static boolean renameRole(ScopeMappingCarrier spec, RoleModel renamed, String newName) {
        String oldName = renamed.getName();
        if (renamed.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientScopeMappings();
            List<String> names = byClient == null ? null : byClient.get(renamed.getContainerId());
            return ListRewrites.replaceInList(names, oldName, newName);
        }
        return ListRewrites.replaceInList(spec.getRealmScopeMappings(), oldName, newName);
    }

    /** Scope-mapping client keys are client ids (= clientIds in this store); resolve either way. */
    private static ClientModel resolveClient(KeycloakSession session, RealmModel realm, String clientKey) {
        ClientModel client = session.clients().getClientById(realm, clientKey);
        return client != null ? client : session.clients().getClientByClientId(realm, clientKey);
    }
}
