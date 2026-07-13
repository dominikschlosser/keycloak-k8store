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

import io.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.keycloak.authorization.model.AbstractAuthorizationModel;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;

/**
 * {@link Policy} over a {@code KeycloakAuthzPolicy} spec. Associations to resources,
 * authorization scopes and other policies are id sets in the spec, resolved through the sibling
 * stores on read (stale ids - never produced by Keycloak itself, but possible in hand-edited
 * CRs - resolve to nothing instead of failing); every mutation re-persists the spec explicitly.
 */
public class PolicyAdapter extends AbstractAuthorizationModel implements Policy {

    private final CrStoreFactory factory;
    private final AuthzPolicySpec spec;

    PolicyAdapter(CrStoreFactory factory, AuthzPolicySpec spec) {
        super(factory);
        this.factory = factory;
        this.spec = spec;
    }

    String getRealmId() {
        return spec.getRealm();
    }

    AuthzPolicySpec spec() {
        return spec;
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public String getType() {
        return spec.getType();
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
    public Logic getLogic() {
        return spec.getLogic() != null ? spec.getLogic() : Logic.POSITIVE;
    }

    @Override
    public void setLogic(Logic logic) {
        throwExceptionIfReadonly();
        spec.setLogic(logic);
        AuthzCrStore.save(spec);
    }

    @Override
    public Map<String, String> getConfig() {
        Map<String, String> config = new HashMap<>();
        if (spec.getConfig() != null) {
            config.putAll(spec.getConfig());
        }
        return Collections.unmodifiableMap(config);
    }

    @Override
    public void setConfig(Map<String, String> config) {
        throwExceptionIfReadonly();
        spec.setConfig(new HashMap<>(config));
        AuthzCrStore.save(spec);
    }

    @Override
    public void removeConfig(String name) {
        throwExceptionIfReadonly();
        if (spec.getConfig() != null && spec.getConfig().keySet().remove(name)) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public void putConfig(String name, String value) {
        throwExceptionIfReadonly();
        if (spec.getConfig() == null) {
            spec.setConfig(new HashMap<>());
        }
        spec.getConfig().put(name, value);
        AuthzCrStore.save(spec);
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
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public void setDescription(String description) {
        throwExceptionIfReadonly();
        spec.setDescription(description);
        AuthzCrStore.save(spec);
    }

    @Override
    public ResourceServer getResourceServer() {
        return factory.resourceServerById(spec.getRealm(), spec.getResourceServer());
    }

    @Override
    public Set<Policy> getAssociatedPolicies() {
        Set<Policy> policies = new HashSet<>();
        if (spec.getAssociatedPolicyIds() != null) {
            for (String policyId : spec.getAssociatedPolicyIds()) {
                Policy policy = factory.policyById(spec.getRealm(), policyId);
                if (policy != null) {
                    policies.add(policy);
                }
            }
        }
        return Collections.unmodifiableSet(policies);
    }

    @Override
    public Set<Resource> getResources() {
        Set<Resource> resources = new HashSet<>();
        if (spec.getResourceIds() != null) {
            for (String resourceId : spec.getResourceIds()) {
                Resource resource = factory.resourceById(spec.getRealm(), resourceId);
                if (resource != null) {
                    resources.add(resource);
                }
            }
        }
        return Collections.unmodifiableSet(resources);
    }

    @Override
    public Set<Scope> getScopes() {
        Set<Scope> scopes = new HashSet<>();
        if (spec.getScopeIds() != null) {
            for (String scopeId : spec.getScopeIds()) {
                Scope scope = factory.scopeById(spec.getRealm(), scopeId);
                if (scope != null) {
                    scopes.add(scope);
                }
            }
        }
        return Collections.unmodifiableSet(scopes);
    }

    @Override
    public String getOwner() {
        return spec.getOwner();
    }

    @Override
    public void setOwner(String owner) {
        throwExceptionIfReadonly();
        spec.setOwner(owner);
        AuthzCrStore.save(spec);
    }

    @Override
    public void addScope(Scope scope) {
        throwExceptionIfReadonly();
        if (spec.getScopeIds() == null) {
            spec.setScopeIds(new LinkedHashSet<>());
        }
        if (spec.getScopeIds().add(scope.getId())) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public void removeScope(Scope scope) {
        throwExceptionIfReadonly();
        if (spec.getScopeIds() != null && spec.getScopeIds().remove(scope.getId())) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public void addAssociatedPolicy(Policy associatedPolicy) {
        throwExceptionIfReadonly();
        if (spec.getAssociatedPolicyIds() == null) {
            spec.setAssociatedPolicyIds(new LinkedHashSet<>());
        }
        if (spec.getAssociatedPolicyIds().add(associatedPolicy.getId())) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public void removeAssociatedPolicy(Policy associatedPolicy) {
        throwExceptionIfReadonly();
        if (spec.getAssociatedPolicyIds() != null
                && spec.getAssociatedPolicyIds().remove(associatedPolicy.getId())) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public void addResource(Resource resource) {
        throwExceptionIfReadonly();
        if (spec.getResourceIds() == null) {
            spec.setResourceIds(new LinkedHashSet<>());
        }
        if (spec.getResourceIds().add(resource.getId())) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public void removeResource(Resource resource) {
        throwExceptionIfReadonly();
        if (spec.getResourceIds() != null && spec.getResourceIds().remove(resource.getId())) {
            AuthzCrStore.save(spec);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Policy that)) {
            return false;
        }
        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }
}
