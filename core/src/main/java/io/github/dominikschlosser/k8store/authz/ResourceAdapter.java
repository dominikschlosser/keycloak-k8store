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
package io.github.dominikschlosser.k8store.authz;

import io.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.keycloak.authorization.model.AbstractAuthorizationModel;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

/**
 * {@link Resource} over a {@code KeycloakAuthzResource} spec. Scope references are stored as an
 * id list in the spec ({@code scopeIds}) and resolved through the scope store on read; every
 * mutation re-persists the spec explicitly.
 */
public class ResourceAdapter extends AbstractAuthorizationModel implements Resource {

    private final CrStoreFactory factory;
    private final AuthzResourceSpec spec;

    ResourceAdapter(CrStoreFactory factory, AuthzResourceSpec spec) {
        super(factory);
        this.factory = factory;
        this.spec = spec;
    }

    String getRealmId() {
        return spec.getRealm();
    }

    AuthzResourceSpec spec() {
        return spec;
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
        throwExceptionIfReadonly();
        spec.setName(name);
        AuthzCrStore.save(spec);
    }

    @Override
    public String getDisplayName() {
        return spec.getDisplayName();
    }

    @Override
    public void setDisplayName(String displayName) {
        throwExceptionIfReadonly();
        spec.setDisplayName(displayName);
        AuthzCrStore.save(spec);
    }

    @Override
    public Set<String> getUris() {
        return spec.getUris() == null ? Set.of() : Collections.unmodifiableSet(spec.getUris());
    }

    @Override
    public void updateUris(Set<String> uris) {
        throwExceptionIfReadonly();
        spec.setUris(uris == null ? null : new LinkedHashSet<>(uris));
        AuthzCrStore.save(spec);
    }

    @Override
    public String getType() {
        return spec.getType();
    }

    @Override
    public void setType(String type) {
        throwExceptionIfReadonly();
        spec.setType(type);
        AuthzCrStore.save(spec);
    }

    @Override
    public List<Scope> getScopes() {
        List<Scope> scopes = new LinkedList<>();
        if (spec.getScopeIds() != null) {
            for (String scopeId : spec.getScopeIds()) {
                Scope scope = factory.scopeById(spec.getRealm(), scopeId);
                if (scope != null) {
                    scopes.add(scope);
                }
            }
        }
        return Collections.unmodifiableList(scopes);
    }

    @Override
    public void updateScopes(Set<Scope> toUpdate) {
        throwExceptionIfReadonly();
        // JPA parity: keep the relative order of surviving entries, append the new ones
        Set<String> wanted = new LinkedHashSet<>();
        for (Scope scope : toUpdate) {
            wanted.add(scope.getId());
        }
        List<String> updated = new ArrayList<>();
        if (spec.getScopeIds() != null) {
            for (String existing : spec.getScopeIds()) {
                if (wanted.remove(existing)) {
                    updated.add(existing);
                }
            }
        }
        updated.addAll(wanted);
        spec.setScopeIds(updated);
        AuthzCrStore.save(spec);
    }

    @Override
    public String getIconUri() {
        return spec.getIconUri();
    }

    @Override
    public void setIconUri(String iconUri) {
        throwExceptionIfReadonly();
        spec.setIconUri(iconUri);
        AuthzCrStore.save(spec);
    }

    @Override
    public ResourceServer getResourceServer() {
        return factory.resourceServerById(spec.getRealm(), spec.getResourceServer());
    }

    @Override
    public String getOwner() {
        return spec.getOwner();
    }

    @Override
    public boolean isOwnerManagedAccess() {
        return Boolean.TRUE.equals(spec.getOwnerManagedAccess());
    }

    @Override
    public void setOwnerManagedAccess(boolean ownerManagedAccess) {
        throwExceptionIfReadonly();
        spec.setOwnerManagedAccess(ownerManagedAccess);
        AuthzCrStore.save(spec);
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        if (spec.getAttributes() == null) {
            return Map.of();
        }
        Map<String, List<String>> copy = new HashMap<>();
        spec.getAttributes().forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String getSingleAttribute(String name) {
        List<String> values = getAttributes().getOrDefault(name, List.of());
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public List<String> getAttribute(String name) {
        List<String> values = getAttributes().getOrDefault(name, List.of());
        // JPA parity: an absent attribute answers null, not an empty list
        return values.isEmpty() ? null : values;
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        throwExceptionIfReadonly();
        if (spec.getAttributes() == null) {
            spec.setAttributes(new HashMap<>());
        }
        spec.getAttributes().put(name, new ArrayList<>(values));
        AuthzCrStore.save(spec);
    }

    @Override
    public void removeAttribute(String name) {
        throwExceptionIfReadonly();
        if (spec.getAttributes() != null && spec.getAttributes().remove(name) != null) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Resource that)) {
            return false;
        }
        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }
}
