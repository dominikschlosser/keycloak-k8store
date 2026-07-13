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
package io.github.dominikschlosser.k8store.group;

import static io.github.dominikschlosser.k8store.spi.StoreInvalidation.GROUP_AFTER_REMOVE;
import static io.github.dominikschlosser.k8store.spi.StoreInvalidation.GROUP_BEFORE_REMOVE;
import static org.keycloak.utils.StreamsUtil.paginatedStream;

import io.github.dominikschlosser.k8store.common.LikePatterns;
import io.github.dominikschlosser.k8store.crd.GroupSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * {@link GroupProvider} serving groups from {@code KeycloakGroup} custom resources.
 *
 * <p>Identity convention (human-readable, GitOps-friendly): group id = group name at creation,
 * fixed afterwards. Hierarchies are stored flat via {@code spec.parentId}; the representation's
 * embedded {@code subGroups} list is ignored on read.
 */
public class GroupCrProvider implements GroupProvider {

    /**
     * {@code spec.type} value of organization-owned groups (an organization's backing group
     * and its organization-scoped subgroups); realm groups leave the field unset. Organization
     * groups are invisible to the regular group queries - upstream JPA filters its {@code
     * KEYCLOAK_GROUP.TYPE} column the same way - and are served through the organization
     * provider instead.
     */
    public static final String ORGANIZATION_TYPE = "organization";

    static GroupModel.Type typeOf(GroupSpec spec) {
        return ORGANIZATION_TYPE.equalsIgnoreCase(spec.getType())
                ? GroupModel.Type.ORGANIZATION
                : GroupModel.Type.REALM;
    }

    private static boolean isRealmGroup(GroupSpec spec) {
        return typeOf(spec) == GroupModel.Type.REALM;
    }

    private final KeycloakSession session;

    public GroupCrProvider(KeycloakSession session) {
        this.session = session;
    }

    /**
     * The hierarchy is stored flat: embedded {@code subGroups} content never reaches this
     * provider - the field is excluded from the CRD schema and from spec deserialization
     * ({@link GroupSpec#getSubGroups()}); author one CR per subgroup linked via
     * {@code spec.parentId} instead.
     */
    private GroupModel adapt(RealmModel realm, GroupSpec spec) {
        return new GroupAdapter(session, realm, spec);
    }

    private Stream<GroupSpec> specs(RealmModel realm) {
        return GroupCrStore.allInRealm(realm.getId()).stream();
    }

    /**
     * The regular group surface serves realm groups only: organization backing groups and
     * organization-scoped subgroups are filtered out of every name/search/count/top-level
     * query, mirroring upstream JPA's {@code type = REALM} predicates. Id-based lookups
     * ({@code getGroupById}, the plain ids stream, role-based lookups) stay type-blind, also
     * like upstream.
     */
    private Stream<GroupSpec> realmGroupSpecs(RealmModel realm) {
        return specs(realm).filter(GroupCrProvider::isRealmGroup);
    }

    private static Predicate<GroupModel> nameMatches(String search, Boolean exact) {
        if (Boolean.TRUE.equals(exact)) {
            return group -> Objects.equals(search, group.getName());
        }
        return group -> LikePatterns.insensitiveLike(group.getName(), "%" + search + "%");
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public GroupModel getGroupById(RealmModel realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return null;
        }
        GroupSpec spec = GroupCrStore.read(realm.getId(), id);
        return spec == null ? null : adapt(realm, spec);
    }

    @Override
    public GroupModel getGroupByName(RealmModel realm, GroupModel parent, String name) {
        if (name == null) {
            return null;
        }
        return realmGroupSpecs(realm)
                .filter(spec -> name.equals(spec.getName()))
                .filter(spec -> parent == null
                        ? spec.getParentId() == null
                        : parent.getId().equals(spec.getParentId()))
                .findFirst()
                .map(spec -> adapt(realm, spec))
                .orElse(null);
    }

    @Override
    public Stream<GroupModel> getGroupsStream(RealmModel realm) {
        return realmGroupSpecs(realm).map(spec -> adapt(realm, spec)).sorted(Comparator.comparing(GroupModel::getName));
    }

    @Override
    public Stream<GroupModel> getGroupsStream(
            RealmModel realm, Stream<String> ids, String search, Integer first, Integer max) {
        // JPA parity: the plain by-ids resolution (no search, no paging) is type-blind, the
        // searching/paginating variants restrict to realm groups
        boolean plainIdResolution = (search == null || search.isEmpty()) && first == null && max == null;
        Stream<GroupSpec> specs =
                ids.map(id -> GroupCrStore.read(realm.getId(), id)).filter(Objects::nonNull);
        if (!plainIdResolution) {
            specs = specs.filter(GroupCrProvider::isRealmGroup);
        }
        Stream<GroupModel> groups =
                specs.map(spec -> adapt(realm, spec)).sorted(Comparator.comparing(GroupModel::getName));
        if (search != null) {
            groups = groups.filter(nameMatches(search, false));
        }
        return paginatedStream(groups, first, max);
    }

    @Override
    public Long getGroupsCount(RealmModel realm, Boolean onlyTopGroups) {
        Stream<GroupSpec> groups = realmGroupSpecs(realm);
        if (Boolean.TRUE.equals(onlyTopGroups)) {
            groups = groups.filter(spec -> spec.getParentId() == null);
        }
        return groups.count();
    }

    @Override
    public Long getGroupsCountByNameContaining(RealmModel realm, String search) {
        return searchForGroupByNameStream(realm, search, false, null, null).count();
    }

    @Override
    public Stream<GroupModel> getGroupsByRoleStream(RealmModel realm, RoleModel role, Integer first, Integer max) {
        Stream<GroupModel> groups = specs(realm)
                .map(spec -> adapt(realm, spec))
                .filter(group -> group.hasDirectRole(role))
                .sorted(Comparator.comparing(GroupModel::getName));
        return paginatedStream(groups, first, max);
    }

    @Override
    public Stream<GroupModel> getTopLevelGroupsStream(
            RealmModel realm, String search, Boolean exact, Integer first, Integer max) {
        Stream<GroupModel> groups = topLevelGroups(realm);
        if (search != null) {
            groups = groups.filter(nameMatches(search, exact));
        }
        return paginatedStream(groups.sorted(Comparator.comparing(GroupModel::getName)), first, max);
    }

    @Override
    public Stream<GroupModel> searchForGroupByNameStream(
            RealmModel realm, String search, Boolean exact, Integer first, Integer max) {
        Stream<GroupModel> groups = topLevelGroups(realm)
                .filter(nameMatches(search, exact))
                .sorted(Comparator.comparing(GroupModel::getName))
                .distinct();
        return paginatedStream(groups, first, max);
    }

    @Override
    public Stream<GroupModel> searchGroupsByAttributes(
            RealmModel realm, Map<String, String> attributes, Integer first, Integer max) {
        Stream<GroupModel> groups = realmGroupSpecs(realm).map(spec -> adapt(realm, spec));
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            groups = groups.filter(group ->
                    group.getAttributeStream(entry.getKey()).anyMatch(value -> value.equals(entry.getValue())));
        }
        return paginatedStream(groups.sorted(Comparator.comparing(GroupModel::getName)), first, max);
    }

    private Stream<GroupModel> topLevelGroups(RealmModel realm) {
        return realmGroupSpecs(realm).filter(spec -> spec.getParentId() == null).map(spec -> adapt(realm, spec));
    }

    // ------------------------------------------------------------------ create / move / remove

    @Override
    public GroupModel createGroup(RealmModel realm, String id, GroupModel.Type type, String name, GroupModel toParent) {
        GroupModel.Type effectiveType = type == null ? GroupModel.Type.REALM : type;
        // name uniqueness is scoped per (parent, type), like upstream's type-filtered name
        // lookups: an organization backing group may share its name with a realm group
        specs(realm)
                .filter(spec -> typeOf(spec) == effectiveType)
                .filter(spec -> name.equals(spec.getName()))
                .filter(spec -> toParent == null
                        ? spec.getParentId() == null
                        : toParent.getId().equals(spec.getParentId()))
                .findAny()
                .ifPresent(spec -> {
                    throw new ModelDuplicateException("Group with name '" + name + "' in realm " + realm.getName()
                            + " already exists for requested parent");
                });

        String groupId = id;
        if (groupId == null) {
            // No explicit id: prefer the name as a readable id, but fall back to a generated one
            // when that id is already taken. This happens for organization subgroups (the same
            // name recurs across organizations) and, crucially, when a group was renamed away
            // from this name - a rename keeps the id, so the old name's id stays occupied and a
            // new group of that name would otherwise be blocked forever. Name uniqueness within
            // the parent was already checked above.
            groupId = GroupCrStore.exists(realm.getId(), name) ? KeycloakModelUtils.generateId() : name;
        } else if (GroupCrStore.exists(realm.getId(), groupId)) {
            throw new ModelDuplicateException("Group exists: " + groupId);
        }

        GroupSpec spec = new GroupSpec();
        spec.setId(groupId);
        spec.setRealm(realm.getId());
        spec.setName(name);
        spec.setParentId(toParent == null ? null : toParent.getId());
        if (effectiveType == GroupModel.Type.ORGANIZATION) {
            spec.setType(ORGANIZATION_TYPE);
        }
        GroupCrStore.save(spec);
        return adapt(realm, spec);
    }

    @Override
    public boolean removeGroup(RealmModel realm, GroupModel group) {
        if (group == null || !GroupCrStore.exists(realm.getId(), group.getId())) {
            return false;
        }
        session.invalidate(GROUP_BEFORE_REMOVE, realm, group);
        GroupCrStore.delete(realm.getId(), group.getId());
        session.invalidate(GROUP_AFTER_REMOVE, realm, group);
        return true;
    }

    @Override
    public void moveGroup(RealmModel realm, GroupModel group, GroupModel toParent) {
        if (toParent != null && group.getId().equals(toParent.getId())) {
            return;
        }
        specs(realm)
                .filter(spec -> typeOf(spec) == group.getType())
                .filter(spec -> group.getName().equals(spec.getName()))
                .filter(spec -> toParent == null
                        ? spec.getParentId() == null
                        : toParent.getId().equals(spec.getParentId()))
                .findAny()
                .ifPresent(spec -> {
                    throw new ModelDuplicateException("Group with name '" + group.getName() + "' in realm "
                            + realm.getName() + " already exists for requested parent");
                });

        GroupModel previousParent = group.getParent();
        if (group.getParentId() != null) {
            group.getParent().removeChild(group);
        }
        group.setParent(toParent);
        if (toParent != null) {
            toParent.addChild(group);
        }

        String newPath = KeycloakModelUtils.buildGroupPath(group);
        String previousPath = KeycloakModelUtils.buildGroupPath(group, previousParent);
        session.getKeycloakSessionFactory().publish(new GroupModel.GroupPathChangeEvent() {
            @Override
            public RealmModel getRealm() {
                return realm;
            }

            @Override
            public GroupModel getGroup() {
                return group;
            }

            @Override
            public String getNewPath() {
                return newPath;
            }

            @Override
            public String getPreviousPath() {
                return previousPath;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return session;
            }
        });
    }

    @Override
    public void addTopLevelGroup(RealmModel realm, GroupModel subGroup) {
        specs(realm)
                .filter(spec -> typeOf(spec) == subGroup.getType())
                .filter(spec -> spec.getParentId() == null)
                .filter(spec -> subGroup.getName().equals(spec.getName()))
                .findAny()
                .ifPresent(spec -> {
                    throw new ModelDuplicateException(
                            "There is already a top level group named '" + subGroup.getName() + "'");
                });
        subGroup.setParent(null);
    }

    // ------------------------------------------------------------------ cascades

    @Override
    public void preRemove(RealmModel realm) {
        specs(realm).forEach(spec -> GroupCrStore.delete(realm.getId(), spec.getId()));
    }

    /** Role removal cascade: drop the removed role from every group's role grants. */
    void roleRemoved(RealmModel realm, RoleModel role) {
        specs(realm)
                .map(spec -> new GroupAdapter(session, realm, spec))
                .filter(group -> group.hasDirectRole(role))
                .forEach(group -> group.deleteRoleMapping(role));
    }

    /** Role rename cascade: rewrite the renamed role in every group's role grants. */
    void roleRenamed(RealmModel realm, RoleModel renamed, String newName) {
        specs(realm)
                .map(spec -> new GroupAdapter(session, realm, spec))
                .filter(group -> group.hasDirectRole(renamed))
                .forEach(group -> group.renameRoleMapping(renamed, newName));
    }

    /**
     * Client-rename cascade: the client id keys the client section of every group's role grants.
     * Rekey the grant map from the old client id to the new one.
     */
    void clientRenamed(RealmModel realm, ClientModel renamed, String newClientId) {
        String oldClientId = renamed.getClientId();
        specs(realm).forEach(spec -> {
            Map<String, List<String>> byClient = spec.getClientRoles();
            if (byClient == null) {
                return;
            }
            List<String> names = byClient.remove(oldClientId);
            if (names != null) {
                byClient.put(newClientId, names);
                GroupCrStore.save(spec);
            }
        });
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
