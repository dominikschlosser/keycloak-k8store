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

import io.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import org.keycloak.authorization.model.AbstractAuthorizationModel;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

/**
 * {@link Scope} (an authorization scope) over a {@code KeycloakAuthzScope} spec; every mutation
 * re-persists the spec explicitly.
 */
public class ScopeAdapter extends AbstractAuthorizationModel implements Scope {

    private final CrStoreFactory factory;
    private final AuthzScopeSpec spec;

    ScopeAdapter(CrStoreFactory factory, AuthzScopeSpec spec) {
        super(factory);
        this.factory = factory;
        this.spec = spec;
    }

    String getRealmId() {
        return spec.getRealm();
    }

    AuthzScopeSpec spec() {
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
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Scope that)) {
            return false;
        }
        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }
}
