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

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_RENAMED;
import static org.keycloak.utils.StreamsUtil.paginatedStream;

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.crd.RoleSpec;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RoleRepresentation.Composites;

/**
 * {@link RoleModel} over a {@link RoleSpec}. The adapter owns a defensive copy of the CR spec;
 * every mutation is persisted explicitly and as a whole - there are no dirty flags and no shared
 * references to rely on.
 *
 * <p>Composites are stored the standard representation way: {@code composites.realm} holds realm
 * role <em>names</em>, {@code composites.client} maps a client id to its role names. Since this
 * store uses human-readable ids (client id = clientId, realm role id = name), the stored values
 * read naturally in hand-authored CRs.
 */
public class RoleAdapter implements RoleModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final RoleSpec spec;

    public RoleAdapter(KeycloakSession session, RealmModel realm, RoleSpec spec) {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.realm = realm;
        this.spec = spec;
    }

    private void persist() {
        RoleCrStore.save(spec);
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
        String oldName = spec.getName();
        if (Objects.equals(oldName, name)) {
            persist();
            return;
        }
        // rewrite name-keyed references (composites, grants, scope mappings, realm default role)
        // before the CR moves - the cascade reads this adapter, which still reports the old name,
        // container and client-role flag at this point
        session.invalidate(ROLE_RENAMED, realm, this, name);
        if (!isClientRole() && realm instanceof RealmAdapter ra) {
            ra.renameDefaultRole(oldName, name);
        }
        // the role id encodes the name (realm role id = name, client role id = clientId:name):
        // move the CR to the new id instead of mutating it in place
        String oldId = spec.getId();
        String newId = renamedId(name);
        RoleCrStore.delete(realm.getId(), oldId);
        spec.setId(newId);
        spec.setName(name);
        persist();
    }

    /** The store id the role takes after being renamed to {@code newName}. */
    private String renamedId(String newName) {
        if (!isClientRole()) {
            return newName;
        }
        ClientModel client = session.clients().getClientById(realm, spec.getContainerId());
        String clientId = client != null ? client.getClientId() : clientIdPrefixOf(spec.getId());
        return clientId + ":" + newName;
    }

    /** Recover the {@code clientId} prefix from a client-role id of the form {@code clientId:name}. */
    private String clientIdPrefixOf(String roleId) {
        int separator = roleId.lastIndexOf(':');
        return separator < 0 ? roleId : roleId.substring(0, separator);
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

    // ------------------------------------------------------------------ composites

    @Override
    public boolean isComposite() {
        Composites composites = spec.getComposites();
        if (composites == null) {
            return false;
        }
        if (composites.getRealm() != null && !composites.getRealm().isEmpty()) {
            return true;
        }
        return composites.getClient() != null
                && composites.getClient().values().stream().anyMatch(names -> names != null && !names.isEmpty());
    }

    @Override
    public void addCompositeRole(RoleModel role) {
        Composites composites = spec.getComposites();
        if (composites == null) {
            composites = new Composites();
            spec.setComposites(composites);
        }
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = composites.getClient();
            if (byClient == null) {
                byClient = new HashMap<>();
                composites.setClient(byClient);
            }
            List<String> names = byClient.computeIfAbsent(role.getContainerId(), k -> new ArrayList<>());
            if (!names.contains(role.getName())) {
                names.add(role.getName());
            }
        } else {
            Set<String> names = composites.getRealm();
            if (names == null) {
                names = new LinkedHashSet<>();
                composites.setRealm(names);
            }
            names.add(role.getName());
        }
        spec.setComposite(true);
        persist();
    }

    @Override
    public void removeCompositeRole(RoleModel role) {
        Composites composites = spec.getComposites();
        if (composites == null) {
            return;
        }
        boolean changed = false;
        if (role.isClientRole()) {
            Map<String, List<String>> byClient = composites.getClient();
            List<String> names = byClient == null ? null : byClient.get(role.getContainerId());
            if (names != null && names.remove(role.getName())) {
                changed = true;
                if (names.isEmpty()) {
                    byClient.remove(role.getContainerId());
                }
            }
        } else if (composites.getRealm() != null && composites.getRealm().remove(role.getName())) {
            changed = true;
        }
        if (changed) {
            spec.setComposite(isComposite());
            persist();
        }
    }

    @Override
    public Stream<RoleModel> getCompositesStream() {
        Composites composites = spec.getComposites();
        if (composites == null) {
            return Stream.empty();
        }
        Stream<RoleModel> realmRoles = composites.getRealm() == null
                ? Stream.empty()
                : composites.getRealm().stream().map(name -> session.roles().getRealmRole(realm, name));
        Stream<RoleModel> clientRoles = composites.getClient() == null
                ? Stream.empty()
                : composites.getClient().entrySet().stream().flatMap(entry -> {
                    ClientModel client = resolveClient(entry.getKey());
                    return client == null || entry.getValue() == null
                            ? Stream.empty()
                            : entry.getValue().stream().map(name -> session.roles().getClientRole(client, name));
                });
        return Stream.concat(realmRoles, clientRoles).filter(Objects::nonNull);
    }

    @Override
    public Stream<RoleModel> getCompositesStream(String search, Integer first, Integer max) {
        Stream<RoleModel> composites = getCompositesStream();
        if (search != null && !search.isBlank()) {
            composites = composites.filter(role -> LikePatterns.insensitiveLike(role.getName(), "%" + search + "%"));
        }
        return paginatedStream(composites, first, max);
    }

    /** Composite client keys are client ids (= clientIds in this store); resolve either way. */
    private ClientModel resolveClient(String clientKey) {
        ClientModel client = session.clients().getClientById(realm, clientKey);
        return client != null ? client : session.clients().getClientByClientId(realm, clientKey);
    }

    // ------------------------------------------------------------------ container

    @Override
    public boolean isClientRole() {
        return Boolean.TRUE.equals(spec.getClientRole());
    }

    @Override
    public String getContainerId() {
        return isClientRole() ? spec.getContainerId() : realm.getId();
    }

    @Override
    public RoleContainerModel getContainer() {
        return isClientRole() ? session.clients().getClientById(realm, spec.getContainerId()) : realm;
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

    // ------------------------------------------------------------------ identity

    @Override
    public boolean hasRole(RoleModel role) {
        return this.equals(role) || KeycloakModelUtils.searchFor(role, this, new HashSet<>());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoleModel other)) {
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
