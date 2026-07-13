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

import io.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import org.keycloak.authorization.model.AbstractAuthorizationModel;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

/**
 * {@link PermissionTicket} over a {@code KeycloakPermissionTicket} spec; granted =
 * {@code grantedTimestamp} set. Mutations re-persist the spec explicitly.
 */
public class PermissionTicketAdapter extends AbstractAuthorizationModel implements PermissionTicket {

    private final CrStoreFactory factory;
    private final PermissionTicketSpec spec;

    PermissionTicketAdapter(CrStoreFactory factory, PermissionTicketSpec spec) {
        super(factory);
        this.factory = factory;
        this.spec = spec;
    }

    String getRealmId() {
        return spec.getRealm();
    }

    PermissionTicketSpec spec() {
        return spec;
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public String getOwner() {
        return spec.getOwner();
    }

    @Override
    public String getRequester() {
        return spec.getRequester();
    }

    @Override
    public Resource getResource() {
        return factory.resourceById(spec.getRealm(), spec.getResourceId());
    }

    @Override
    public Scope getScope() {
        return spec.getScopeId() == null ? null : factory.scopeById(spec.getRealm(), spec.getScopeId());
    }

    @Override
    public boolean isGranted() {
        return spec.getGrantedTimestamp() != null;
    }

    @Override
    public Long getCreatedTimestamp() {
        return spec.getCreatedTimestamp();
    }

    @Override
    public Long getGrantedTimestamp() {
        return spec.getGrantedTimestamp();
    }

    @Override
    public void setGrantedTimestamp(Long millis) {
        throwExceptionIfReadonly();
        spec.setGrantedTimestamp(millis);
        AuthzCrStore.save(spec);
    }

    @Override
    public ResourceServer getResourceServer() {
        return factory.resourceServerById(spec.getRealm(), spec.getResourceServer());
    }

    @Override
    public Policy getPolicy() {
        return spec.getPolicyId() == null ? null : factory.policyById(spec.getRealm(), spec.getPolicyId());
    }

    @Override
    public void setPolicy(Policy policy) {
        throwExceptionIfReadonly();
        spec.setPolicyId(policy == null ? null : policy.getId());
        AuthzCrStore.save(spec);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionTicket that)) {
            return false;
        }
        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }
}
