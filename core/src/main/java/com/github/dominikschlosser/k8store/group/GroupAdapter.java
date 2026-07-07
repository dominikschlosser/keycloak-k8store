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
package com.github.dominikschlosser.k8store.group;

import com.github.dominikschlosser.k8store.crd.GroupSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.utils.RoleUtils;
import org.keycloak.organization.OrganizationProvider;

/**
 * {@link GroupModel} over a {@link GroupSpec}. The adapter owns a defensive copy of the CR spec;
 * every mutation is persisted explicitly and as a whole - there are no dirty flags and no shared
 * references to rely on.
 *
 * <p>The hierarchy is flat: subgroups are separate CRs linked through {@code spec.parentId}.
 * Role grants are stored the standard representation way - {@code realmRoles} holds realm role
 * <em>names</em>, {@code clientRoles} maps a client id to its role names.
 */
public class GroupAdapter implements GroupModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final GroupSpec spec;

    public GroupAdapter(KeycloakSession session, RealmModel realm, GroupSpec spec) {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.realm = realm;
        this.spec = spec;
    }

    private void persist() {
        GroupCrStore.save(spec);
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public String getName() {
        return spec.getName();
    }

    @Override
    public void setName(String name) {
        spec.setName(name);
        persist();
    }

    @Override
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public void setDescription(String description) {
        spec.setDescription(description);
        persist();
    }

    // ------------------------------------------------------------------ attributes

    @Override
    public Map<String, List<String>> getAttributes() {
        Map<String, List<String>> attributes = spec.getAttributes();
        return attributes == null ? Map.of() : attributes;
    }

    @Override
    public Stream<String> getAttributeStream(String name) {
        List<String> values = getAttributes().get(name);
        return values == null ? Stream.empty() : values.stream();
    }

    @Override
    public String getFirstAttribute(String name) {
        return getAttributeStream(name).findFirst().orElse(null);
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        Map<String, List<String>> attributes = spec.getAttributes();
        if (attributes == null) {
            attributes = new HashMap<>();
            spec.setAttributes(attributes);
        }
        attributes.put(name, new ArrayList<>(values));
        persist();
    }

    @Override
    public void setSingleAttribute(String name, String value) {
        List<String> values = new ArrayList<>(1);
        values.add(value);
        setAttribute(name, values);
    }

    @Override
    public void removeAttribute(String name) {
        Map<String, List<String>> attributes = spec.getAttributes();
        if (attributes != null && attributes.remove(name) != null) {
            persist();
        }
    }

    // ------------------------------------------------------------------ hierarchy

    @Override
    public String getParentId() {
        return spec.getParentId();
    }

    @Override
    public GroupModel getParent() {
        String parentId = spec.getParentId();
        return parentId == null ? null : session.groups().getGroupById(realm, parentId);
    }

    @Override
    public void setParent(GroupModel parent) {
        if (parent == null) {
            spec.setParentId(null);
            persist();
        } else if (!getId().equals(parent.getId())) {
            spec.setParentId(parent.getId());
            persist();
        }
    }

    @Override
    public void addChild(GroupModel subGroup) {
        subGroup.setParent(this);
    }

    @Override
    public void removeChild(GroupModel subGroup) {
        if (getId().equals(subGroup.getParentId())) {
            subGroup.setParent(null);
        }
    }

    @Override
    public Stream<GroupModel> getSubGroupsStream() {
        return GroupCrStore.allInRealm(realm.getId()).stream()
                .filter(child -> getId().equals(child.getParentId()))
                .map(child -> new GroupAdapter(session, realm, child))
                .map(GroupModel.class::cast)
                .sorted(Comparator.comparing(GroupModel::getName));
    }

    @Override
    public Type getType() {
        return GroupCrProvider.typeOf(spec);
    }

    @Override
    public OrganizationModel getOrganization() {
        String organizationId = spec.getOrganizationId();
        if (organizationId == null) {
            return null;
        }
        OrganizationProvider organizations = session.getProvider(OrganizationProvider.class);
        return organizations == null ? null : organizations.getById(organizationId);
    }

    /**
     * Stamps the owning organization onto this adapter's <em>live</em> spec (never a fresh
     * store copy - an in-flight adapter would clobber it on its next persist) and persists.
     * Called by the organization provider right after it creates a backing/organization group
     * through the group provider.
     */
    public void linkOrganization(String organizationId) {
        spec.setType(GroupCrProvider.ORGANIZATION_TYPE);
        spec.setOrganizationId(organizationId);
        persist();
    }

    // ------------------------------------------------------------------ role grants

    @Override
    public void grantRole(RoleModel role) {
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientRoles();
            if (byClient == null) {
                byClient = new HashMap<>();
                spec.setClientRoles(byClient);
            }
            List<String> names = byClient.computeIfAbsent(role.getContainerId(), k -> new ArrayList<>());
            if (!names.contains(role.getName())) {
                names.add(role.getName());
            }
        } else {
            List<String> names = spec.getRealmRoles();
            if (names == null) {
                names = new ArrayList<>();
                spec.setRealmRoles(names);
            }
            if (!names.contains(role.getName())) {
                names.add(role.getName());
            }
        }
        persist();
    }

    @Override
    public void deleteRoleMapping(RoleModel role) {
        boolean changed = false;
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientRoles();
            List<String> names = byClient == null ? null : byClient.get(role.getContainerId());
            if (names != null && names.remove(role.getName())) {
                changed = true;
                if (names.isEmpty()) {
                    byClient.remove(role.getContainerId());
                }
            }
        } else {
            List<String> names = spec.getRealmRoles();
            changed = names != null && names.remove(role.getName());
        }
        if (changed) {
            persist();
        }
    }

    /**
     * Role rename cascade: swap the old role name for the new one in this group's grants,
     * preserving list position so the grant ordering stays stable. The role's container id is
     * unchanged by a rename, so the client-section key stays the same.
     */
    public void renameRoleMapping(RoleModel renamed, String newName) {
        String oldName = renamed.getName();
        boolean changed;
        if (renamed.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientRoles();
            List<String> names = byClient == null ? null : byClient.get(renamed.getContainerId());
            changed = replaceInList(names, oldName, newName);
        } else {
            changed = replaceInList(spec.getRealmRoles(), oldName, newName);
        }
        if (changed) {
            persist();
        }
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
    public Stream<RoleModel> getRoleMappingsStream() {
        Stream<RoleModel> realmRoles = spec.getRealmRoles() == null
                ? Stream.empty()
                : spec.getRealmRoles().stream().map(name -> session.roles().getRealmRole(realm, name));
        Stream<RoleModel> clientRoles = spec.getClientRoles() == null
                ? Stream.empty()
                : spec.getClientRoles().entrySet().stream().flatMap(entry -> {
                    ClientModel client = resolveClient(entry.getKey());
                    return client == null || entry.getValue() == null
                            ? Stream.empty()
                            : entry.getValue().stream().map(name -> session.roles().getClientRole(client, name));
                });
        return Stream.concat(realmRoles, clientRoles).filter(Objects::nonNull);
    }

    @Override
    public Stream<RoleModel> getRealmRoleMappingsStream() {
        return getRoleMappingsStream().filter(role -> !role.isClientRole());
    }

    @Override
    public Stream<RoleModel> getClientRoleMappingsStream(ClientModel client) {
        return getRoleMappingsStream()
                .filter(RoleModel::isClientRole)
                .filter(role -> client.getId().equals(role.getContainerId()));
    }

    @Override
    public boolean hasDirectRole(RoleModel role) {
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientRoles();
            List<String> names = byClient == null ? null : byClient.get(role.getContainerId());
            return names != null && names.contains(role.getName());
        }
        return spec.getRealmRoles() != null && spec.getRealmRoles().contains(role.getName());
    }

    @Override
    public boolean hasRole(RoleModel role) {
        if (RoleUtils.hasRole(getRoleMappingsStream(), role)) {
            return true;
        }
        GroupModel parent = getParent();
        return parent != null && parent.hasRole(role);
    }

    /** Role-grant client keys are client ids (= clientIds in this store); resolve either way. */
    private ClientModel resolveClient(String clientKey) {
        ClientModel client = session.clients().getClientById(realm, clientKey);
        return client != null ? client : session.clients().getClientByClientId(realm, clientKey);
    }

    // ------------------------------------------------------------------ identity

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupModel other)) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return getName() + "@" + getId();
    }
}
