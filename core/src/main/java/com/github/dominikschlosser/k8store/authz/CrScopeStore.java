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
import com.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * {@link ScopeStore} over {@code KeycloakAuthzScope} custom resources. Query semantics mirror
 * upstream JPA: names are unique per resource server (enforced here at create time - there is
 * no database constraint to fall back on), the name filter is a case-insensitive contains match
 * with {@code *} wildcards, results are ordered by name.
 */
class CrScopeStore implements ScopeStore {

    private final CrStoreFactory factory;

    CrScopeStore(CrStoreFactory factory) {
        this.factory = factory;
    }

    private Stream<AuthzScopeSpec> specs(ResourceServer resourceServer) {
        String realmId = factory.realmOf(resourceServer);
        if (realmId == null) {
            return Stream.empty();
        }
        return AuthzCrStore.scopes(realmId).stream()
                .filter(spec -> resourceServer == null || resourceServer.getId().equals(spec.getResourceServer()));
    }

    @Override
    public Scope create(ResourceServer resourceServer, String id, String name) {
        if (findByName(resourceServer, name) != null) {
            throw new ModelDuplicateException("Scope with name [" + name + "] already exists in resource server ["
                    + resourceServer.getId() + "]");
        }
        AuthzScopeSpec spec = new AuthzScopeSpec();
        spec.setId(id != null ? id : KeycloakModelUtils.generateId());
        spec.setRealm(factory.realmOf(resourceServer));
        spec.setResourceServer(resourceServer.getId());
        spec.setName(name);
        return factory.wrap(AuthzCrStore.save(spec));
    }

    @Override
    public void delete(String id) {
        ScopeAdapter scope = factory.scopeById(factory.contextRealmId(), id);
        if (scope != null) {
            AuthzCrStore.deleteScope(scope.getRealmId(), id);
        }
    }

    @Override
    public Scope findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        ScopeAdapter scope = factory.scopeById(factory.realmOf(resourceServer), id);
        if (scope == null
                || (resourceServer != null
                        && !resourceServer.getId().equals(scope.spec().getResourceServer()))) {
            return null;
        }
        return scope;
    }

    @Override
    public Scope findByName(ResourceServer resourceServer, String name) {
        return specs(resourceServer)
                .filter(spec -> Objects.equals(name, spec.getName()))
                .findFirst()
                .map(factory::wrap)
                .orElse(null);
    }

    @Override
    public List<Scope> findByResourceServer(ResourceServer resourceServer) {
        return specs(resourceServer).map(factory::wrap).map(Scope.class::cast).toList();
    }

    @Override
    public List<Scope> findByResourceServer(
            ResourceServer resourceServer,
            Map<Scope.FilterOption, String[]> attributes,
            Integer firstResult,
            Integer maxResults) {
        Stream<AuthzScopeSpec> matches = specs(resourceServer).filter(spec -> matchesFilters(spec, attributes));
        return paginatedStream(
                        matches.sorted(Comparator.comparing(
                                AuthzScopeSpec::getName, Comparator.nullsFirst(Comparator.naturalOrder()))),
                        firstResult,
                        maxResults)
                .map(factory::wrap)
                .map(Scope.class::cast)
                .toList();
    }

    private boolean matchesFilters(AuthzScopeSpec spec, Map<Scope.FilterOption, String[]> attributes) {
        for (Map.Entry<Scope.FilterOption, String[]> filter : attributes.entrySet()) {
            String[] value = filter.getValue();
            boolean matches =
                    switch (filter.getKey()) {
                        case ID -> List.of(value).contains(spec.getId());
                        case NAME -> LikePatterns.containsTerm(spec.getName(), value[0], false);
                    };
            if (!matches) {
                return false;
            }
        }
        return true;
    }
}
