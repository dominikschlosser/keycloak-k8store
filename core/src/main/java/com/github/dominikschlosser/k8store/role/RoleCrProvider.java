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

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_AFTER_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_BEFORE_REMOVE;
import static org.keycloak.utils.StreamsUtil.paginatedStream;

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.RoleProvider;
import org.keycloak.representations.idm.RoleRepresentation.Composites;

/**
 * {@link RoleProvider} serving roles from {@code KeycloakRole} custom resources.
 *
 * <p>Identity convention (human-readable, GitOps-friendly): realm role id = role name, client
 * role id = {@code <clientId>:<name>}. These ids surface in admin URLs and token claims. Renaming
 * a role moves its CR to the new id and cascades the new name into every name-keyed reference
 * (composites, grants, scope mappings, the realm default role).
 */
public class RoleCrProvider implements RoleProvider {

    private static final Logger LOG = Logger.getLogger(RoleCrProvider.class);

    private final KeycloakSession session;

    public RoleCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private RoleModel adapt(RealmModel realm, RoleSpec spec) {
        return new RoleAdapter(session, realm, spec);
    }

    private Stream<RoleSpec> specs(RealmModel realm) {
        return RoleCrStore.allInRealm(realm.getId()).stream();
    }

    private static boolean isClientRoleSpec(RoleSpec spec) {
        return Boolean.TRUE.equals(spec.getClientRole());
    }

    private static Predicate<RoleModel> nameOrDescriptionLike(String search) {
        String pattern = "%" + search + "%";
        return role -> LikePatterns.insensitiveLike(role.getName(), pattern)
                || LikePatterns.insensitiveLike(role.getDescription(), pattern);
    }

    // ------------------------------------------------------------------ create

    @Override
    public RoleModel addRealmRole(RealmModel realm, String id, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name cannot be null or blank");
        }
        if (getRealmRole(realm, name) != null) {
            throw new ModelDuplicateException(
                    "Role with the same name exists: " + name + " for realm " + realm.getName());
        }
        String roleId = id == null ? name : id;
        if (RoleCrStore.exists(realm.getId(), roleId)) {
            throw new ModelDuplicateException("Role exists: " + roleId);
        }

        RoleSpec spec = new RoleSpec();
        spec.setId(roleId);
        spec.setRealm(realm.getId());
        spec.setName(name);
        spec.setContainerId(realm.getId());
        RoleCrStore.save(spec);
        return adapt(realm, spec);
    }

    @Override
    public RoleModel addClientRole(ClientModel client, String id, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name cannot be null or blank");
        }
        if (getClientRole(client, name) != null) {
            throw new ModelDuplicateException(
                    "Role with the same name exists: " + name + " for client " + client.getClientId());
        }
        RealmModel realm = client.getRealm();
        String roleId = id == null ? name : id;
        if (client.getClientId() != null) {
            roleId = client.getClientId() + ":" + roleId;
        }
        if (RoleCrStore.exists(realm.getId(), roleId)) {
            throw new ModelDuplicateException("Role exists: " + roleId);
        }

        RoleSpec spec = new RoleSpec();
        spec.setId(roleId);
        spec.setRealm(realm.getId());
        spec.setName(name);
        spec.setClientRole(true);
        spec.setContainerId(client.getId());
        RoleCrStore.save(spec);
        return adapt(realm, spec);
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public RoleModel getRoleById(RealmModel realm, String id) {
        if (realm == null || realm.getId() == null || id == null || id.isBlank()) {
            return null;
        }
        RoleSpec spec = RoleCrStore.read(realm.getId(), id);
        return spec == null ? null : adapt(realm, spec);
    }

    @Override
    public RoleModel getRealmRole(RealmModel realm, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return specs(realm)
                .filter(spec -> !isClientRoleSpec(spec))
                .filter(spec -> name.equals(spec.getName()))
                .findFirst()
                .map(spec -> adapt(realm, spec))
                .orElse(null);
    }

    @Override
    public RoleModel getClientRole(ClientModel client, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        RealmModel realm = client.getRealm();
        return specs(realm)
                .filter(RoleCrProvider::isClientRoleSpec)
                .filter(spec -> client.getId().equals(spec.getContainerId()))
                .filter(spec -> name.equals(spec.getName()))
                .findFirst()
                .map(spec -> adapt(realm, spec))
                .orElse(null);
    }

    @Override
    public Stream<RoleModel> getRealmRolesStream(RealmModel realm, Integer first, Integer max) {
        Stream<RoleModel> roles = specs(realm)
                .filter(spec -> !isClientRoleSpec(spec))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(RoleModel::getName));
        return paginatedStream(roles, first, max);
    }

    @Override
    public Stream<RoleModel> getClientRolesStream(ClientModel client, Integer first, Integer max) {
        RealmModel realm = client.getRealm();
        Stream<RoleModel> roles = specs(realm)
                .filter(RoleCrProvider::isClientRoleSpec)
                .filter(spec -> client.getId().equals(spec.getContainerId()))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(RoleModel::getName));
        return paginatedStream(roles, first, max);
    }

    @Override
    public Stream<RoleModel> getRolesStream(
            RealmModel realm, Stream<String> ids, String search, Integer first, Integer max) {
        Stream<RoleModel> roles = ids
                .map(id -> RoleCrStore.read(realm.getId(), id))
                .filter(Objects::nonNull)
                .map(spec -> adapt(realm, spec));
        if (search != null && !search.isBlank()) {
            roles = roles.filter(role -> LikePatterns.insensitiveLike(role.getName(), "%" + search + "%"));
        }
        return paginatedStream(roles, first, max);
    }

    // ------------------------------------------------------------------ search

    @Override
    public Stream<RoleModel> searchForRolesStream(RealmModel realm, String search, Integer first, Integer max) {
        if (search == null) {
            return Stream.empty();
        }
        Stream<RoleModel> roles = specs(realm)
                .filter(spec -> !isClientRoleSpec(spec))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(RoleModel::getName));
        if (!search.isBlank()) {
            roles = roles.filter(nameOrDescriptionLike(search));
        }
        return paginatedStream(roles, first, max);
    }

    @Override
    public Stream<RoleModel> searchForClientRolesStream(ClientModel client, String search, Integer first, Integer max) {
        if (search == null) {
            return Stream.empty();
        }
        RealmModel realm = client.getRealm();
        Stream<RoleModel> roles = specs(realm)
                .filter(RoleCrProvider::isClientRoleSpec)
                .filter(spec -> client.getId().equals(spec.getContainerId()))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(RoleModel::getName));
        if (!search.isBlank()) {
            roles = roles.filter(nameOrDescriptionLike(search));
        }
        return paginatedStream(roles, first, max);
    }

    @Override
    public Stream<RoleModel> searchForClientRolesStream(
            RealmModel realm, Stream<String> ids, String search, Integer first, Integer max) {
        if (search == null) {
            return Stream.empty();
        }
        Stream<RoleModel> roles = ids
                .map(id -> RoleCrStore.read(realm.getId(), id))
                .filter(Objects::nonNull)
                .filter(RoleCrProvider::isClientRoleSpec)
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(RoleModel::getName));
        if (!search.isBlank()) {
            roles = roles.filter(nameOrDescriptionLike(search));
        }
        return paginatedStream(roles, first, max);
    }

    @Override
    public Stream<RoleModel> searchForClientRolesStream(
            RealmModel realm, String search, Stream<String> excludedIds, Integer first, Integer max) {
        if (search == null) {
            return Stream.empty();
        }
        Set<String> excluded = excludedIds == null ? Set.of() : excludedIds.collect(Collectors.toSet());
        Stream<RoleModel> roles = specs(realm)
                .filter(RoleCrProvider::isClientRoleSpec)
                .filter(spec -> !excluded.contains(spec.getId()))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(RoleModel::getName));
        if (!search.isBlank()) {
            roles = roles.filter(nameOrDescriptionLike(search));
        }
        return paginatedStream(roles, first, max);
    }

    // ------------------------------------------------------------------ removal & cascades

    @Override
    public boolean removeRole(RoleModel role) {
        RealmModel realm = role.isClientRole()
                ? ((ClientModel) role.getContainer()).getRealm()
                : (RealmModel) role.getContainer();
        session.invalidate(ROLE_BEFORE_REMOVE, realm, role);
        RoleCrStore.delete(realm.getId(), role.getId());
        session.invalidate(ROLE_AFTER_REMOVE, realm, role);
        return true;
    }

    @Override
    public void removeRoles(RealmModel realm) {
        getRealmRolesStream(realm).forEach(this::removeRole);
    }

    @Override
    public void removeRoles(ClientModel client) {
        getClientRolesStream(client).forEach(this::removeRole);
    }

    /** Realm removal: drop every role CR of the realm, composites and all. */
    void realmRemoved(RealmModel realm) {
        specs(realm).forEach(spec -> RoleCrStore.delete(realm.getId(), spec.getId()));
    }

    /**
     * Role removal cascade: purge the removed role from every other role's composites. Composite
     * entries are stored by name (realm section) or client id + name (client section), so the
     * removed role's container decides where to look.
     */
    void roleRemoved(RealmModel realm, RoleModel removed) {
        specs(realm).forEach(spec -> {
            Composites composites = spec.getComposites();
            if (composites == null) {
                return;
            }
            boolean changed;
            if (removed.isClientRole()) {
                Map<String, List<String>> byClient = composites.getClient();
                List<String> names = byClient == null ? null : byClient.get(removed.getContainerId());
                changed = names != null && names.remove(removed.getName());
                if (changed && names.isEmpty()) {
                    byClient.remove(removed.getContainerId());
                }
            } else {
                changed = composites.getRealm() != null && composites.getRealm().remove(removed.getName());
            }
            if (changed) {
                LOG.tracef("Dropping removed role %s from composites of %s", removed.getName(), spec.getId());
                RoleCrStore.save(spec);
            }
        });
    }

    /**
     * Role rename cascade: swap the old role name for the new one in every other role's composites.
     * Client-role composites are keyed by the role's container id (unchanged by a rename); the
     * client-section list keeps its position on swap. Realm composites are a set, so the swap is a
     * remove-then-add and does not preserve order.
     */
    void roleRenamed(RealmModel realm, RoleModel renamed, String newName) {
        String oldName = renamed.getName();
        specs(realm).forEach(spec -> {
            Composites composites = spec.getComposites();
            if (composites == null) {
                return;
            }
            boolean changed;
            if (renamed.isClientRole()) {
                Map<String, List<String>> byClient = composites.getClient();
                List<String> names = byClient == null ? null : byClient.get(renamed.getContainerId());
                changed = replaceInList(names, oldName, newName);
            } else {
                Set<String> names = composites.getRealm();
                changed = names != null && names.remove(oldName);
                if (changed) {
                    names.add(newName);
                }
            }
            if (changed) {
                LOG.tracef("Rewriting renamed role %s to %s in composites of %s",
                        oldName, newName, spec.getId());
                RoleCrStore.save(spec);
            }
        });
    }

    private static boolean replaceInList(List<String> names, String oldValue, String newValue) {
        if (names == null) {
            return false;
        }
        int index = names.indexOf(oldValue);
        if (index < 0) {
            return false;
        }
        names.set(index, newValue);
        return true;
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
