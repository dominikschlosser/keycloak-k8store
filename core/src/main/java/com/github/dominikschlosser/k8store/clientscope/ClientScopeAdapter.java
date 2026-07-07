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
package com.github.dominikschlosser.k8store.clientscope;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_SCOPE_RENAMED;

import com.github.dominikschlosser.k8store.common.ProtocolMapperSupport;
import com.github.dominikschlosser.k8store.common.ScopeMappingSupport;
import com.github.dominikschlosser.k8store.crd.ClientScopeSpec;
import com.github.dominikschlosser.k8store.realm.RealmAdapter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.RoleUtils;

/**
 * {@link ClientScopeModel} over a {@link ClientScopeSpec}. The adapter owns a defensive copy of
 * the CR spec; every mutation is persisted explicitly and as a whole - there are no dirty flags
 * and no shared references to rely on.
 *
 * <p>Identity: the model id <em>is</em> the scope name (human-readable, GitOps-friendly).
 * Renaming the scope moves the CR - the old resource is deleted and the spec is re-persisted
 * under the new name; name-based references (client assignment lists, realm default-scope ids)
 * keep the usual staleness caveat of this store.
 */
public class ClientScopeAdapter implements ClientScopeModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final ClientScopeSpec spec;

    public ClientScopeAdapter(KeycloakSession session, RealmModel realm, ClientScopeSpec spec) {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.realm = realm;
        this.spec = spec;
    }

    private void persist() {
        ClientScopeCrStore.save(spec);
    }

    @Override
    public String getId() {
        return spec.getName();
    }

    @Override
    public String getName() {
        return spec.getName();
    }

    @Override
    public void setName(String name) {
        String normalized = KeycloakModelUtils.convertClientScopeName(name);
        String current = spec.getName();
        if (Objects.equals(current, normalized)) {
            return;
        }
        // rewrite name-keyed references (client assignment lists, user consents) before the CR
        // moves, so the handlers still resolve the old name
        session.invalidate(CLIENT_SCOPE_RENAMED, realm, current, normalized);
        if (realm instanceof RealmAdapter ra) {
            ra.renameDefaultClientScope(current, normalized);
        }
        // the scope name is the store id: move the CR instead of mutating it in place
        ClientScopeCrStore.delete(spec.getRealm(), current);
        spec.setName(normalized);
        spec.setId(normalized);
        persist();
    }

    @Override
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public void setDescription(String description) {
        if (!Objects.equals(spec.getDescription(), description)) {
            spec.setDescription(description);
            persist();
        }
    }

    @Override
    public String getProtocol() {
        return spec.getProtocol();
    }

    @Override
    public void setProtocol(String protocol) {
        if (!Objects.equals(spec.getProtocol(), protocol)) {
            spec.setProtocol(protocol);
            persist();
        }
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    // ------------------------------------------------------------------ attributes

    @Override
    public void setAttribute(String name, String value) {
        Map<String, String> attributes = spec.getAttributes();
        if (attributes == null) {
            attributes = new HashMap<>();
            spec.setAttributes(attributes);
        }
        if (!Objects.equals(attributes.get(name), value)) {
            attributes.put(name, value);
            persist();
        }
    }

    @Override
    public void removeAttribute(String name) {
        Map<String, String> attributes = spec.getAttributes();
        if (attributes != null && attributes.remove(name) != null) {
            persist();
        }
    }

    @Override
    public String getAttribute(String name) {
        return spec.getAttributes() == null ? null : spec.getAttributes().get(name);
    }

    @Override
    public Map<String, String> getAttributes() {
        return spec.getAttributes() == null ? Map.of() : new HashMap<>(spec.getAttributes());
    }

    // ------------------------------------------------------------------ protocol mappers

    @Override
    public Stream<ProtocolMapperModel> getProtocolMappersStream() {
        return ProtocolMapperSupport.stream(spec);
    }

    @Override
    public ProtocolMapperModel addProtocolMapper(ProtocolMapperModel model) {
        return ProtocolMapperSupport.add(spec, model, this::persist);
    }

    @Override
    public void removeProtocolMapper(ProtocolMapperModel mapping) {
        ProtocolMapperSupport.remove(spec, mapping, this::persist);
    }

    @Override
    public void updateProtocolMapper(ProtocolMapperModel mapping) {
        ProtocolMapperSupport.update(spec, mapping, this::persist);
    }

    @Override
    public ProtocolMapperModel getProtocolMapperById(String id) {
        return ProtocolMapperSupport.getById(spec, id);
    }

    @Override
    public ProtocolMapperModel getProtocolMapperByName(String protocol, String name) {
        return ProtocolMapperSupport.getByName(spec, protocol, name);
    }

    // ------------------------------------------------------------------ scope mappings

    @Override
    public Stream<RoleModel> getScopeMappingsStream() {
        return ScopeMappingSupport.stream(session, realm, spec);
    }

    @Override
    public Stream<RoleModel> getRealmScopeMappingsStream() {
        return ScopeMappingSupport.realmStream(session, realm, spec);
    }

    @Override
    public void addScopeMapping(RoleModel role) {
        ScopeMappingSupport.add(spec, role, this::persist);
    }

    @Override
    public void deleteScopeMapping(RoleModel role) {
        ScopeMappingSupport.delete(spec, role, this::persist);
    }

    @Override
    public boolean hasScope(RoleModel role) {
        return RoleUtils.hasRole(getScopeMappingsStream(), role);
    }

    // ------------------------------------------------------------------ identity semantics

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientScopeModel other)) {
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
        return getName() + "@" + realm.getId();
    }
}
