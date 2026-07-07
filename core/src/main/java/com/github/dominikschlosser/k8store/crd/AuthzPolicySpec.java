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
package com.github.dominikschlosser.k8store.crd;

import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;

/**
 * Spec of a {@code KeycloakAuthzPolicy} custom resource: one authorization policy or permission
 * of a resource server. Scoped by realm + {@code resourceServer} (the owning client's clientId);
 * the id is a generated UUID, names are unique per resource server. The policy's references to
 * resources, authorization scopes and other policies are plain id sets ({@link #getResourceIds()
 * resourceIds}, {@link #getScopeIds() scopeIds}, {@link #getAssociatedPolicyIds()
 * associatedPolicyIds} - the JPA junction tables in CR shape), so there is no recursion in the
 * CRD schema. The type-specific settings (e.g. a role policy's roles, a client policy's
 * clients) live in the {@link #getConfig() config} map exactly as Keycloak's policy providers
 * store them - JSON-array strings of ids/names.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthzPolicySpec {

    /** Generated UUID, the store id; defaults to {@code metadata.name}. */
    private String id;

    /** Name of the realm. */
    private String realm;

    /** clientId of the owning resource server. */
    private String resourceServer;

    private String name;
    private String description;

    /** Policy provider type: {@code role}, {@code client}, {@code resource}, {@code scope}, … */
    private String type;

    /** Keycloak's default is UNANIMOUS. */
    private DecisionStrategy decisionStrategy;

    /** Keycloak's default is POSITIVE. */
    private Logic logic;

    /** Owner (user id) of UMA user-managed permissions; null for server-managed policies. */
    private String owner;

    /** Provider-specific settings, Keycloak's native policy-config shape. */
    private Map<String, String> config;

    /** Ids of the resources a permission applies to. */
    private Set<String> resourceIds;

    /** Ids of the authorization scopes a scope permission applies to. */
    private Set<String> scopeIds;

    /** Ids of the policies a permission delegates its decision to ("apply policies"). */
    private Set<String> associatedPolicyIds;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getResourceServer() {
        return resourceServer;
    }

    public void setResourceServer(String resourceServer) {
        this.resourceServer = resourceServer;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public DecisionStrategy getDecisionStrategy() {
        return decisionStrategy;
    }

    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        this.decisionStrategy = decisionStrategy;
    }

    public Logic getLogic() {
        return logic;
    }

    public void setLogic(Logic logic) {
        this.logic = logic;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public void setConfig(Map<String, String> config) {
        this.config = config;
    }

    public Set<String> getResourceIds() {
        return resourceIds;
    }

    public void setResourceIds(Set<String> resourceIds) {
        this.resourceIds = resourceIds;
    }

    public Set<String> getScopeIds() {
        return scopeIds;
    }

    public void setScopeIds(Set<String> scopeIds) {
        this.scopeIds = scopeIds;
    }

    public Set<String> getAssociatedPolicyIds() {
        return associatedPolicyIds;
    }

    public void setAssociatedPolicyIds(Set<String> associatedPolicyIds) {
        this.associatedPolicyIds = associatedPolicyIds;
    }
}
