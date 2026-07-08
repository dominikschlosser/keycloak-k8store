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
package com.github.dominikschlosser.k8store.user;

import com.github.dominikschlosser.k8store.crd.UserSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.RoleUtils;

/**
 * {@link UserModel} over a {@link UserSpec}. The adapter owns a defensive copy of the CR spec;
 * every mutation is persisted explicitly and as a whole. Setters persist only when the value
 * actually changes.
 *
 * <p>Identity: the model id is {@code spec.id} - the lowercased username at creation, immutable
 * afterwards (renaming a user does <em>not</em> move the CR, so token {@code sub} claims and
 * session references stay valid). {@code username} and {@code email} are normalized to lower
 * case on write, matching upstream JPA behavior.
 *
 * <p>{@code firstName}/{@code lastName}/{@code email}/{@code username} masquerade as attributes
 * in the attribute API - Keycloak's user-profile layer reads and writes them that way.
 */
public class UserAdapter implements UserModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final UserSpec spec;

    public UserAdapter(KeycloakSession session, RealmModel realm, UserSpec spec) {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.realm = realm;
        this.spec = spec;
    }

    /** The backing spec - the provider's credential store mutates it through here. */
    UserSpec spec() {
        return spec;
    }

    void persist() {
        UserCrStore.save(spec);
    }

    // ------------------------------------------------------------------ identity

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public String getUsername() {
        return spec.getUsername();
    }

    @Override
    public void setUsername(String username) {
        String normalized = KeycloakModelUtils.toLowerCaseSafe(username);
        if (!Objects.equals(spec.getUsername(), normalized)) {
            spec.setUsername(normalized);
            persist();
        }
    }

    @Override
    public Long getCreatedTimestamp() {
        return spec.getCreatedTimestamp();
    }

    @Override
    public void setCreatedTimestamp(Long timestamp) {
        if (!Objects.equals(spec.getCreatedTimestamp(), timestamp)) {
            spec.setCreatedTimestamp(timestamp);
            persist();
        }
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(spec.isEnabled());
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (spec.isEnabled() == null || spec.isEnabled() != enabled) {
            spec.setEnabled(enabled);
            persist();
        }
    }

    @Override
    public String getFirstName() {
        return spec.getFirstName();
    }

    @Override
    public void setFirstName(String firstName) {
        if (!Objects.equals(spec.getFirstName(), firstName)) {
            spec.setFirstName(firstName);
            persist();
        }
    }

    @Override
    public String getLastName() {
        return spec.getLastName();
    }

    @Override
    public void setLastName(String lastName) {
        if (!Objects.equals(spec.getLastName(), lastName)) {
            spec.setLastName(lastName);
            persist();
        }
    }

    @Override
    public String getEmail() {
        return spec.getEmail();
    }

    @Override
    public void setEmail(String email) {
        String normalized = KeycloakModelUtils.toLowerCaseSafe(email);
        if (!Objects.equals(spec.getEmail(), normalized)) {
            spec.setEmail(normalized);
            persist();
        }
    }

    @Override
    public boolean isEmailVerified() {
        return Boolean.TRUE.equals(spec.isEmailVerified());
    }

    @Override
    public void setEmailVerified(boolean verified) {
        if (spec.isEmailVerified() == null || spec.isEmailVerified() != verified) {
            spec.setEmailVerified(verified);
            persist();
        }
    }

    // ------------------------------------------------------------------ attributes

    @Override
    public void setSingleAttribute(String name, String value) {
        if (setFirstClassAttribute(name, value)) {
            return;
        }
        if (value == null) {
            removeAttribute(name);
            return;
        }
        Map<String, List<String>> attributes = attributesForWrite();
        List<String> single = new ArrayList<>(1);
        single.add(value);
        if (!Objects.equals(attributes.get(name), single)) {
            attributes.put(name, single);
            persist();
        }
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        if (setFirstClassAttribute(name, values == null || values.isEmpty() ? null : values.get(0))) {
            return;
        }
        if (values == null || values.isEmpty()) {
            removeAttribute(name);
            return;
        }
        Map<String, List<String>> attributes = attributesForWrite();
        if (!Objects.equals(attributes.get(name), values)) {
            attributes.put(name, new ArrayList<>(values));
            persist();
        }
    }

    /** Routes the first-class fields the user-profile layer addresses as attributes. */
    private boolean setFirstClassAttribute(String name, String value) {
        switch (name == null ? "" : name) {
            case UserModel.FIRST_NAME -> setFirstName(value);
            case UserModel.LAST_NAME -> setLastName(value);
            case UserModel.EMAIL -> setEmail(value);
            case UserModel.USERNAME -> setUsername(value);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void removeAttribute(String name) {
        Map<String, List<String>> attributes = spec.getAttributes();
        if (attributes != null && attributes.remove(name) != null) {
            persist();
        }
    }

    @Override
    public String getFirstAttribute(String name) {
        String firstClass = firstClassAttribute(name);
        if (firstClass != null || isFirstClassAttribute(name)) {
            return firstClass;
        }
        Map<String, List<String>> attributes = spec.getAttributes();
        List<String> values = attributes == null ? null : attributes.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Stream<String> getAttributeStream(String name) {
        if (isFirstClassAttribute(name)) {
            String value = firstClassAttribute(name);
            return value == null ? Stream.empty() : Stream.of(value);
        }
        Map<String, List<String>> attributes = spec.getAttributes();
        List<String> values = attributes == null ? null : attributes.get(name);
        return values == null ? Stream.empty() : new ArrayList<>(values).stream();
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        MultivaluedHashMap<String, String> all = new MultivaluedHashMap<>();
        if (spec.getAttributes() != null) {
            spec.getAttributes().forEach((name, values) -> {
                if (values != null) {
                    all.put(name, new ArrayList<>(values));
                }
            });
        }
        // first-class fields masquerade as attributes (upstream parity)
        all.add(UserModel.FIRST_NAME, spec.getFirstName());
        all.add(UserModel.LAST_NAME, spec.getLastName());
        all.add(UserModel.EMAIL, spec.getEmail());
        all.add(UserModel.USERNAME, spec.getUsername());
        return all;
    }

    private static boolean isFirstClassAttribute(String name) {
        return UserModel.FIRST_NAME.equals(name)
                || UserModel.LAST_NAME.equals(name)
                || UserModel.EMAIL.equals(name)
                || UserModel.USERNAME.equals(name);
    }

    private String firstClassAttribute(String name) {
        return switch (name == null ? "" : name) {
            case UserModel.FIRST_NAME -> spec.getFirstName();
            case UserModel.LAST_NAME -> spec.getLastName();
            case UserModel.EMAIL -> spec.getEmail();
            case UserModel.USERNAME -> spec.getUsername();
            default -> null;
        };
    }

    private Map<String, List<String>> attributesForWrite() {
        Map<String, List<String>> attributes = spec.getAttributes();
        if (attributes == null) {
            attributes = new HashMap<>();
            spec.setAttributes(attributes);
        }
        return attributes;
    }

    // ------------------------------------------------------------------ required actions

    @Override
    public Stream<String> getRequiredActionsStream() {
        List<String> actions = spec.getRequiredActions();
        return actions == null ? Stream.empty() : new ArrayList<>(actions).stream();
    }

    @Override
    public void addRequiredAction(String action) {
        if (action == null) {
            return;
        }
        List<String> actions = spec.getRequiredActions();
        if (actions == null) {
            actions = new ArrayList<>();
            spec.setRequiredActions(actions);
        }
        if (!actions.contains(action)) {
            actions.add(action);
            persist();
        }
    }

    @Override
    public void removeRequiredAction(String action) {
        List<String> actions = spec.getRequiredActions();
        if (actions != null && actions.remove(action)) {
            persist();
        }
    }

    // ------------------------------------------------------------------ groups (stored as group ids)

    @Override
    public Stream<GroupModel> getGroupsStream() {
        List<String> groupIds = spec.getGroups();
        if (groupIds == null || groupIds.isEmpty()) {
            return Stream.empty();
        }
        return new ArrayList<>(groupIds)
                .stream().map(id -> session.groups().getGroupById(realm, id)).filter(Objects::nonNull);
    }

    @Override
    public void joinGroup(GroupModel group) {
        List<String> groupIds = spec.getGroups();
        if (groupIds == null) {
            groupIds = new ArrayList<>();
            spec.setGroups(groupIds);
        }
        if (!groupIds.contains(group.getId())) {
            groupIds.add(group.getId());
            persist();
        }
    }

    @Override
    public void leaveGroup(GroupModel group) {
        List<String> groupIds = spec.getGroups();
        if (groupIds != null && groupIds.remove(group.getId())) {
            persist();
        }
    }

    @Override
    public boolean isMemberOf(GroupModel group) {
        return RoleUtils.isMember(getGroupsStream(), group);
    }

    // ------------------------------------------------------------------ role mappings (stored by name)

    @Override
    public Stream<RoleModel> getRealmRoleMappingsStream() {
        List<String> names = spec.getRealmRoles();
        if (names == null || names.isEmpty()) {
            return Stream.empty();
        }
        return new ArrayList<>(names)
                .stream().map(name -> session.roles().getRealmRole(realm, name)).filter(Objects::nonNull);
    }

    @Override
    public Stream<RoleModel> getClientRoleMappingsStream(ClientModel client) {
        Map<String, List<String>> byClient = spec.getClientRoles();
        List<String> names = byClient == null ? null : byClient.get(client.getId());
        if (names == null || names.isEmpty()) {
            return Stream.empty();
        }
        return new ArrayList<>(names)
                .stream()
                        .map(name -> session.roles().getClientRole(client, name))
                        .filter(Objects::nonNull);
    }

    @Override
    public Stream<RoleModel> getRoleMappingsStream() {
        Map<String, List<String>> byClient = spec.getClientRoles();
        Stream<RoleModel> clientRoles = byClient == null
                ? Stream.empty()
                : new ArrayList<>(byClient.keySet())
                        .stream()
                                .map(clientId -> session.clients().getClientById(realm, clientId))
                                .filter(Objects::nonNull)
                                .flatMap(this::getClientRoleMappingsStream);
        return Stream.concat(getRealmRoleMappingsStream(), clientRoles);
    }

    @Override
    public boolean hasDirectRole(RoleModel role) {
        return getRoleMappingsStream().anyMatch(r -> Objects.equals(r.getId(), role.getId()));
    }

    @Override
    public boolean hasRole(RoleModel role) {
        return RoleUtils.hasRole(getRoleMappingsStream(), role)
                || RoleUtils.hasRoleFromGroup(getGroupsStream(), role, true);
    }

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
                persist();
            }
        } else {
            List<String> names = spec.getRealmRoles();
            if (names == null) {
                names = new ArrayList<>();
                spec.setRealmRoles(names);
            }
            if (!names.contains(role.getName())) {
                names.add(role.getName());
                persist();
            }
        }
    }

    @Override
    public void deleteRoleMapping(RoleModel role) {
        boolean changed;
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = spec.getClientRoles();
            List<String> names = byClient == null ? null : byClient.get(role.getContainerId());
            changed = names != null && names.remove(role.getName());
            if (changed && names.isEmpty()) {
                byClient.remove(role.getContainerId());
            }
        } else {
            changed = spec.getRealmRoles() != null && spec.getRealmRoles().remove(role.getName());
        }
        if (changed) {
            persist();
        }
    }

    // ------------------------------------------------------------------ links

    @Override
    public String getFederationLink() {
        return spec.getFederationLink();
    }

    @Override
    public void setFederationLink(String link) {
        if (!Objects.equals(spec.getFederationLink(), link)) {
            spec.setFederationLink(link);
            persist();
        }
    }

    @Override
    public String getServiceAccountClientLink() {
        return spec.getServiceAccountClientId();
    }

    @Override
    public void setServiceAccountClientLink(String clientInternalId) {
        if (!Objects.equals(spec.getServiceAccountClientId(), clientInternalId)) {
            spec.setServiceAccountClientId(clientInternalId);
            persist();
        }
    }

    // ------------------------------------------------------------------ credentials

    @Override
    public SubjectCredentialManager credentialManager() {
        return session.users().getUserCredentialManager(this);
    }

    // ------------------------------------------------------------------ identity semantics

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserModel other)) {
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
        return getUsername() + "@" + realm.getId();
    }
}
