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
package com.github.dominikschlosser.k8store.authz;

import static org.keycloak.utils.StreamsUtil.paginatedStream;

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.authorization.fgap.AdminPermissionsSchema;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.authorization.AbstractPolicyRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;

/**
 * {@link PolicyStore} over {@code KeycloakAuthzPolicy} custom resources. Query semantics mirror
 * the upstream JPA store's named queries: names are unique per resource server (enforced at
 * create time), the scope-driven lookups are restricted to {@code type == "scope"} permissions,
 * {@code findByResourceType} matches the {@code defaultResourceType} config entry,
 * {@code findDependentPolicies} answers the policies whose associated-policy set contains the
 * given policy, and the fine-grained-admin variant additionally filters by scope name,
 * resource-type config and associated-policy config values (SQL-LIKE semantics preserved).
 * The generic {@code find} keeps JPA's implicit owner-is-null filter unless an owner filter is
 * part of the query.
 */
class CrPolicyStore implements PolicyStore {

    /** Policy types that represent permissions (JPA's {@code PERMISSION} filter). */
    private static final Set<String> PERMISSION_TYPES = Set.of("resource", "scope", "uma");

    private static final String DEFAULT_RESOURCE_TYPE_CONFIG = "defaultResourceType";

    private final CrStoreFactory factory;

    CrPolicyStore(CrStoreFactory factory) {
        this.factory = factory;
    }

    private Stream<AuthzPolicySpec> specs(ResourceServer resourceServer) {
        String realmId = factory.realmOf(resourceServer);
        if (realmId == null) {
            return Stream.empty();
        }
        return AuthzCrStore.policies(realmId).stream()
                .filter(spec -> resourceServer == null || resourceServer.getId().equals(spec.getResourceServer()));
    }

    @Override
    public Policy create(ResourceServer resourceServer, AbstractPolicyRepresentation representation) {
        if (findByName(resourceServer, representation.getName()) != null) {
            throw new ModelDuplicateException("Policy with name [" + representation.getName()
                    + "] already exists in resource server [" + resourceServer.getId() + "]");
        }
        AuthzPolicySpec spec = new AuthzPolicySpec();
        spec.setId(representation.getId() != null ? representation.getId() : KeycloakModelUtils.generateId());
        spec.setRealm(factory.realmOf(resourceServer));
        spec.setResourceServer(resourceServer.getId());
        spec.setType(representation.getType());
        spec.setName(representation.getName());
        // upstream entity defaults; RepresentationToModel overwrites them right after creation
        spec.setDecisionStrategy(DecisionStrategy.UNANIMOUS);
        spec.setLogic(Logic.POSITIVE);
        return factory.wrap(AuthzCrStore.save(spec));
    }

    @Override
    public void delete(String id) {
        PolicyAdapter policy = factory.policyById(factory.contextRealmId(), id);
        if (policy != null) {
            AuthzCrStore.deletePolicy(policy.getRealmId(), id);
        }
    }

    @Override
    public Policy findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        PolicyAdapter policy = factory.policyById(factory.realmOf(resourceServer), id);
        if (policy == null
                || (resourceServer != null
                        && !resourceServer.getId().equals(policy.spec().getResourceServer()))) {
            return null;
        }
        return policy;
    }

    @Override
    public Policy findByName(ResourceServer resourceServer, String name) {
        return specs(resourceServer)
                .filter(spec -> Objects.equals(name, spec.getName()))
                .findFirst()
                .map(factory::wrap)
                .orElse(null);
    }

    @Override
    public List<Policy> findByResourceServer(ResourceServer resourceServer) {
        return specs(resourceServer).map(factory::wrap).map(Policy.class::cast).toList();
    }

    @Override
    public List<Policy> find(
            ResourceServer resourceServer,
            Map<Policy.FilterOption, String[]> attributes,
            Integer firstResult,
            Integer maxResults) {
        Stream<AuthzPolicySpec> matches = specs(resourceServer).filter(spec -> matchesFilters(spec, attributes));
        if (!attributes.containsKey(Policy.FilterOption.OWNER)
                && !attributes.containsKey(Policy.FilterOption.ANY_OWNER)) {
            // JPA parity: without an owner filter only server-managed policies are returned
            matches = matches.filter(spec -> spec.getOwner() == null);
        }
        return paginatedStream(
                        matches.sorted(Comparator.comparing(
                                AuthzPolicySpec::getName, Comparator.nullsFirst(Comparator.naturalOrder()))),
                        firstResult,
                        maxResults)
                .map(factory::wrap)
                .map(Policy.class::cast)
                .toList();
    }

    private boolean matchesFilters(AuthzPolicySpec spec, Map<Policy.FilterOption, String[]> attributes) {
        for (Map.Entry<Policy.FilterOption, String[]> filter : attributes.entrySet()) {
            String[] value = filter.getValue();
            // Arrays.asList, not List.of: the matched attribute may be null (e.g. the owner of
            // server-managed policies), and List.of's contains(null) throws
            boolean matches =
                    switch (filter.getKey()) {
                        case ID -> Arrays.asList(value).contains(spec.getId());
                        case OWNER -> Arrays.asList(value).contains(spec.getOwner());
                        case ANY_OWNER -> true;
                        case RESOURCE_ID ->
                            spec.getResourceIds() != null
                                    && spec.getResourceIds().stream().anyMatch(Arrays.asList(value)::contains);
                        case SCOPE_ID ->
                            spec.getScopeIds() != null
                                    && spec.getScopeIds().stream().anyMatch(Arrays.asList(value)::contains);
                        case PERMISSION -> PERMISSION_TYPES.contains(spec.getType()) == Boolean.parseBoolean(value[0]);
                        case CONFIG -> {
                            if (value.length != 2) {
                                throw new IllegalArgumentException(
                                        "Config filter option requires value with two items: [config_name, expected_config_value]");
                            }
                            String configValue = spec.getConfig() == null
                                    ? null
                                    : spec.getConfig().get(value[0]);
                            yield configValue != null && LikePatterns.containsTerm(configValue, value[1], true);
                        }
                        case TYPE -> LikePatterns.containsTerm(spec.getType(), value[0], false);
                        case NAME -> LikePatterns.containsTerm(spec.getName(), value[0], false);
                    };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void findByResource(ResourceServer resourceServer, Resource resource, Consumer<Policy> consumer) {
        specs(resourceServer)
                .filter(spec ->
                        spec.getResourceIds() != null && spec.getResourceIds().contains(resource.getId()))
                .map(factory::wrap)
                .forEach(consumer);
    }

    @Override
    public void findByResourceType(ResourceServer resourceServer, String resourceType, Consumer<Policy> consumer) {
        specs(resourceServer)
                .filter(spec -> spec.getConfig() != null
                        && LikePatterns.like(spec.getConfig().get(DEFAULT_RESOURCE_TYPE_CONFIG), resourceType))
                .map(factory::wrap)
                .forEach(consumer);
    }

    @Override
    public List<Policy> findByScopes(ResourceServer resourceServer, List<Scope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Set<String> scopeIds = ids(scopes);
        return specs(resourceServer)
                .filter(spec -> "scope".equals(spec.getType()))
                .filter(spec -> spec.getScopeIds() != null
                        && spec.getScopeIds().stream().anyMatch(scopeIds::contains))
                .map(factory::wrap)
                .map(Policy.class::cast)
                .toList();
    }

    @Override
    public void findByScopes(
            ResourceServer resourceServer, Resource resource, List<Scope> scopes, Consumer<Policy> consumer) {
        Set<String> scopeIds = ids(scopes);
        Stream<AuthzPolicySpec> matches = specs(resourceServer)
                .filter(spec -> "scope".equals(spec.getType()))
                .filter(spec -> spec.getScopeIds() != null
                        && spec.getScopeIds().stream().anyMatch(scopeIds::contains));
        if (resource == null) {
            // JPA parity: scope permissions not attached to any resource, excluding the
            // resource-type permissions of the fine-grained-admin schema
            matches = matches.filter(spec -> spec.getResourceIds() == null
                            || spec.getResourceIds().isEmpty())
                    .filter(spec ->
                            spec.getConfig() == null || !spec.getConfig().containsKey(DEFAULT_RESOURCE_TYPE_CONFIG));
        } else {
            matches = matches.filter(spec ->
                    spec.getResourceIds() != null && spec.getResourceIds().contains(resource.getId()));
        }
        matches.map(factory::wrap).forEach(consumer);
    }

    @Override
    public List<Policy> findByType(ResourceServer resourceServer, String type) {
        return specs(resourceServer)
                .filter(spec -> Objects.equals(type, spec.getType()))
                .map(factory::wrap)
                .map(Policy.class::cast)
                .toList();
    }

    @Override
    public List<Policy> findDependentPolicies(ResourceServer resourceServer, String policyId) {
        return specs(resourceServer)
                .filter(spec -> spec.getAssociatedPolicyIds() != null
                        && spec.getAssociatedPolicyIds().contains(policyId))
                .map(factory::wrap)
                .map(Policy.class::cast)
                .toList();
    }

    @Override
    public Stream<Policy> findDependentPolicies(
            ResourceServer resourceServer,
            String resourceType,
            String groupResourceType,
            String associatedPolicyType,
            String configKey,
            String configValue) {
        return findDependentPolicies(
                resourceServer, resourceType, groupResourceType, associatedPolicyType, configKey, List.of(configValue));
    }

    @Override
    public Stream<Policy> findDependentPolicies(
            ResourceServer resourceServer,
            String resourceType,
            String groupResourceType,
            String associatedPolicyType,
            String configKey,
            List<String> configValues) {
        // the fine-grained-admin dependency query: resource-type permissions carrying the
        // relevant view scope whose associated policy of the given type references one of the
        // given config values (e.g. a role id inside a role policy's "roles" JSON array)
        String scopeName = AdminPermissionsSchema.GROUPS.getType().equals(groupResourceType) ? "view-members" : "view";
        String realmId = factory.realmOf(resourceServer);
        return specs(resourceServer)
                .filter(spec -> spec.getConfig() != null
                        && LikePatterns.like(spec.getConfig().get(DEFAULT_RESOURCE_TYPE_CONFIG), resourceType))
                .filter(spec -> spec.getScopeIds() != null
                        && spec.getScopeIds().stream()
                                .map(scopeId -> AuthzCrStore.scope(realmId, scopeId))
                                .anyMatch(scope -> scope != null && scopeName.equals(scope.getName())))
                .filter(spec -> spec.getAssociatedPolicyIds() != null
                        && spec.getAssociatedPolicyIds().stream()
                                .map(associatedId -> AuthzCrStore.policy(realmId, associatedId))
                                .anyMatch(associated -> associated != null
                                        && Objects.equals(associatedPolicyType, associated.getType())
                                        && matchesAssociatedConfig(associated, configKey, configValues)))
                .map(factory::wrap)
                .map(Policy.class::cast);
    }

    private static boolean matchesAssociatedConfig(
            AuthzPolicySpec associated, String configKey, List<String> configValues) {
        if (configKey == null) {
            return true;
        }
        String configValue =
                associated.getConfig() == null ? null : associated.getConfig().get(configKey);
        if (configValue == null) {
            return false;
        }
        return configValues.stream().anyMatch(value -> LikePatterns.containsTerm(configValue, value, true));
    }

    private static Set<String> ids(List<Scope> scopes) {
        return scopes.stream().map(Scope::getId).collect(Collectors.toSet());
    }
}
