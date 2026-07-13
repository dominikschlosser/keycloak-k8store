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

import io.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import org.keycloak.authorization.model.AbstractAuthorizationModel;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode;

/**
 * {@link ResourceServer} over a {@code KeycloakResourceServer} spec. The id is the owning
 * client's clientId (== the client's store id in this store); every mutation re-persists the
 * spec explicitly.
 */
public class ResourceServerAdapter extends AbstractAuthorizationModel implements ResourceServer {

    private final ResourceServerSpec spec;

    ResourceServerAdapter(CrStoreFactory storeFactory, ResourceServerSpec spec) {
        super(storeFactory);
        this.spec = spec;
    }

    String getRealmId() {
        return spec.getRealm();
    }

    @Override
    public String getId() {
        return spec.getClientId();
    }

    @Override
    public String getClientId() {
        return spec.getClientId();
    }

    @Override
    public boolean isAllowRemoteResourceManagement() {
        return Boolean.TRUE.equals(spec.getAllowRemoteResourceManagement());
    }

    @Override
    public void setAllowRemoteResourceManagement(boolean allowRemoteResourceManagement) {
        throwExceptionIfReadonly();
        spec.setAllowRemoteResourceManagement(allowRemoteResourceManagement);
        AuthzCrStore.save(spec);
    }

    @Override
    public PolicyEnforcementMode getPolicyEnforcementMode() {
        return spec.getPolicyEnforcementMode() != null
                ? spec.getPolicyEnforcementMode()
                : PolicyEnforcementMode.ENFORCING;
    }

    @Override
    public void setPolicyEnforcementMode(PolicyEnforcementMode policyEnforcementMode) {
        throwExceptionIfReadonly();
        spec.setPolicyEnforcementMode(policyEnforcementMode);
        AuthzCrStore.save(spec);
    }

    @Override
    public DecisionStrategy getDecisionStrategy() {
        return spec.getDecisionStrategy() != null ? spec.getDecisionStrategy() : DecisionStrategy.UNANIMOUS;
    }

    @Override
    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        throwExceptionIfReadonly();
        spec.setDecisionStrategy(decisionStrategy);
        AuthzCrStore.save(spec);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceServer that)) {
            return false;
        }
        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }
}
