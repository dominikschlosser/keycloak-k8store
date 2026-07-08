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
import com.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * {@link ResourceStore} over {@code KeycloakAuthzResource} custom resources. Query semantics
 * mirror upstream JPA: names are unique per (resource server, owner) - enforced at create time,
 * there is no database constraint to fall back on - the name/type filters are case-insensitive
 * contains matches with {@code *} wildcards, {@code findByType} defaults the owner to the
 * resource server itself, and {@code findByTypeInstance} answers the user-owned instances
 * (owner != resource server) of a type.
 */
class CrResourceStore implements ResourceStore {

    private final CrStoreFactory factory;

    CrResourceStore(CrStoreFactory factory) {
        this.factory = factory;
    }

    private Stream<AuthzResourceSpec> specs(ResourceServer resourceServer) {
        String realmId = factory.realmOf(resourceServer);
        if (realmId == null) {
            return Stream.empty();
        }
        return AuthzCrStore.resources(realmId).stream()
                .filter(spec -> resourceServer == null || resourceServer.getId().equals(spec.getResourceServer()));
    }

    @Override
    public Resource create(ResourceServer resourceServer, String id, String name, String owner) {
        if (findByName(resourceServer, name, owner) != null) {
            throw new ModelDuplicateException("Resource with name [" + name + "] already exists for owner [" + owner
                    + "] in resource server [" + resourceServer.getId() + "]");
        }
        AuthzResourceSpec spec = new AuthzResourceSpec();
        spec.setId(id != null ? id : KeycloakModelUtils.generateId());
        spec.setRealm(factory.realmOf(resourceServer));
        spec.setResourceServer(resourceServer.getId());
        spec.setName(name);
        spec.setOwner(owner);
        return factory.wrap(AuthzCrStore.save(spec));
    }

    @Override
    public void delete(String id) {
        ResourceAdapter resource = factory.resourceById(factory.contextRealmId(), id);
        if (resource != null) {
            AuthzCrStore.deleteResource(resource.getRealmId(), id);
        }
    }

    @Override
    public Resource findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        ResourceAdapter resource = factory.resourceById(factory.realmOf(resourceServer), id);
        if (resource == null
                || (resourceServer != null
                        && !resourceServer.getId().equals(resource.spec().getResourceServer()))) {
            return null;
        }
        return resource;
    }

    @Override
    public void findByOwner(ResourceServer resourceServer, String ownerId, Consumer<Resource> consumer) {
        specs(resourceServer)
                .filter(spec -> Objects.equals(ownerId, spec.getOwner()))
                .map(factory::wrap)
                .forEach(consumer);
    }

    @Override
    public List<Resource> findByResourceServer(ResourceServer resourceServer) {
        return specs(resourceServer)
                .map(factory::wrap)
                .map(Resource.class::cast)
                .toList();
    }

    @Override
    public List<Resource> find(
            ResourceServer resourceServer,
            Map<Resource.FilterOption, String[]> attributes,
            Integer firstResult,
            Integer maxResults) {
        Stream<AuthzResourceSpec> matches = specs(resourceServer)
                .filter(spec -> matchesFilters(spec, attributes))
                .sorted(Comparator.comparing(
                        AuthzResourceSpec::getName, Comparator.nullsFirst(Comparator.naturalOrder())));
        return paginatedStream(matches, firstResult, maxResults)
                .map(factory::wrap)
                .map(Resource.class::cast)
                .toList();
    }

    private boolean matchesFilters(AuthzResourceSpec spec, Map<Resource.FilterOption, String[]> attributes) {
        for (Map.Entry<Resource.FilterOption, String[]> filter : attributes.entrySet()) {
            String[] value = filter.getValue();
            // Arrays.asList, not List.of: contains(null) must answer false, not throw
            boolean matches =
                    switch (filter.getKey()) {
                        case ID -> Arrays.asList(value).contains(spec.getId());
                        case OWNER -> Arrays.asList(value).contains(spec.getOwner());
                        case SCOPE_ID ->
                            spec.getScopeIds() != null
                                    && spec.getScopeIds().stream().anyMatch(Arrays.asList(value)::contains);
                        case OWNER_MANAGED_ACCESS ->
                            Boolean.TRUE.equals(spec.getOwnerManagedAccess()) == Boolean.parseBoolean(value[0]);
                        case URI ->
                            spec.getUris() != null
                                    && spec.getUris().stream().anyMatch(uri -> uri.equalsIgnoreCase(value[0]));
                        case URI_NOT_NULL ->
                            spec.getUris() != null && !spec.getUris().isEmpty();
                        case NAME -> LikePatterns.containsTerm(spec.getName(), value[0], false);
                        case TYPE -> LikePatterns.containsTerm(spec.getType(), value[0], false);
                        case EXACT_NAME ->
                            spec.getName() != null && spec.getName().equalsIgnoreCase(value[0]);
                    };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void findByScopes(ResourceServer resourceServer, Set<Scope> scopes, Consumer<Resource> consumer) {
        Set<String> scopeIds = ids(scopes);
        specs(resourceServer)
                .filter(spec -> spec.getScopeIds() != null
                        && spec.getScopeIds().stream().anyMatch(scopeIds::contains))
                .map(factory::wrap)
                .forEach(consumer);
    }

    @Override
    public Resource findByName(ResourceServer resourceServer, String name, String ownerId) {
        return specs(resourceServer)
                .filter(spec -> Objects.equals(ownerId, spec.getOwner()) && Objects.equals(name, spec.getName()))
                .findFirst()
                .map(factory::wrap)
                .orElse(null);
    }

    @Override
    public void findByType(ResourceServer resourceServer, String type, Consumer<Resource> consumer) {
        findByType(resourceServer, type, resourceServer == null ? null : resourceServer.getId(), consumer);
    }

    @Override
    public void findByType(ResourceServer resourceServer, String type, String owner, Consumer<Resource> consumer) {
        specs(resourceServer)
                .filter(spec -> Objects.equals(type, spec.getType()))
                .filter(spec -> owner == null || Objects.equals(owner, spec.getOwner()))
                .map(factory::wrap)
                .forEach(consumer);
    }

    @Override
    public void findByTypeInstance(ResourceServer resourceServer, String type, Consumer<Resource> consumer) {
        String serverId = resourceServer == null ? null : resourceServer.getId();
        specs(resourceServer)
                .filter(spec -> Objects.equals(type, spec.getType()) && !Objects.equals(serverId, spec.getOwner()))
                .map(factory::wrap)
                .forEach(consumer);
    }

    private static Set<String> ids(Set<Scope> scopes) {
        return scopes.stream().map(Scope::getId).collect(Collectors.toSet());
    }
}
