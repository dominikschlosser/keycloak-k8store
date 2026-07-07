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
import com.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * {@link PermissionTicketStore} over {@code KeycloakPermissionTicket} custom resources - the one
 * always-writable authorization kind (UMA runtime data). Query semantics mirror upstream JPA,
 * including the caller restriction: server-scoped {@code find}/{@code count} queries of a
 * non-admin caller only see tickets the current user owns or requested, unless the caller is
 * the resource server's own service account.
 */
class CrPermissionTicketStore implements PermissionTicketStore {

    private final CrStoreFactory factory;

    CrPermissionTicketStore(CrStoreFactory factory) {
        this.factory = factory;
    }

    private Stream<PermissionTicketSpec> specs(ResourceServer resourceServer) {
        String realmId = factory.realmOf(resourceServer);
        if (realmId == null) {
            return Stream.empty();
        }
        return AuthzCrStore.tickets(realmId).stream()
                .filter(spec -> resourceServer == null || resourceServer.getId().equals(spec.getResourceServer()));
    }

    @Override
    public long count(ResourceServer resourceServer, Map<PermissionTicket.FilterOption, String> attributes) {
        return filtered(resourceServer, attributes).count();
    }

    @Override
    public PermissionTicket create(ResourceServer resourceServer, Resource resource, Scope scope, String requester) {
        PermissionTicketSpec spec = new PermissionTicketSpec();
        spec.setId(KeycloakModelUtils.generateId());
        spec.setRealm(factory.realmOf(resourceServer));
        spec.setResourceServer(resourceServer.getId());
        spec.setResourceId(resource.getId());
        spec.setScopeId(scope == null ? null : scope.getId());
        spec.setRequester(requester);
        spec.setOwner(resource.getOwner());
        spec.setCreatedTimestamp(Time.currentTimeMillis());
        return factory.wrap(AuthzCrStore.save(spec));
    }

    @Override
    public void delete(String id) {
        PermissionTicketAdapter ticket = factory.ticketById(factory.contextRealmId(), id);
        if (ticket != null) {
            AuthzCrStore.deleteTicket(ticket.getRealmId(), id);
        }
    }

    @Override
    public PermissionTicket findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        PermissionTicketAdapter ticket = factory.ticketById(factory.realmOf(resourceServer), id);
        if (ticket == null || (resourceServer != null
                && !resourceServer.getId().equals(ticket.spec().getResourceServer()))) {
            return null;
        }
        return ticket;
    }

    @Override
    public List<PermissionTicket> findByResource(ResourceServer resourceServer, Resource resource) {
        return specs(resourceServer)
                .filter(spec -> Objects.equals(resource.getId(), spec.getResourceId()))
                .map(factory::wrap)
                .map(PermissionTicket.class::cast)
                .toList();
    }

    @Override
    public List<PermissionTicket> findByScope(ResourceServer resourceServer, Scope scope) {
        if (scope == null) {
            return List.of();
        }
        return specs(resourceServer)
                .filter(spec -> Objects.equals(scope.getId(), spec.getScopeId()))
                .map(factory::wrap)
                .map(PermissionTicket.class::cast)
                .toList();
    }

    @Override
    public List<PermissionTicket> find(ResourceServer resourceServer,
                                       Map<PermissionTicket.FilterOption, String> attributes,
                                       Integer firstResult, Integer maxResults) {
        Stream<PermissionTicketSpec> matches = filtered(resourceServer, attributes)
                .sorted(Comparator.comparing(PermissionTicketSpec::getId));
        return paginatedStream(matches, firstResult, maxResults)
                .map(factory::wrap)
                .map(PermissionTicket.class::cast)
                .toList();
    }

    private Stream<PermissionTicketSpec> filtered(ResourceServer resourceServer,
                                                  Map<PermissionTicket.FilterOption, String> attributes) {
        Stream<PermissionTicketSpec> matches = specs(resourceServer);
        String callerRestriction = callerRestriction(resourceServer, attributes);
        if (callerRestriction != null) {
            matches = matches.filter(spec -> callerRestriction.equals(spec.getOwner())
                    || callerRestriction.equals(spec.getRequester()));
        }
        return matches.filter(spec -> matchesFilters(spec, attributes));
    }

    /**
     * JPA parity: a server-scoped query of a non-admin caller is restricted to the current
     * user's own tickets (as owner or requester) - except when the caller is the resource
     * server's service account, which sees everything. Returns the restricting user id, or null
     * for no restriction.
     */
    private String callerRestriction(ResourceServer resourceServer,
                                     Map<PermissionTicket.FilterOption, String> attributes) {
        if (resourceServer == null
                || Boolean.parseBoolean(attributes.get(PermissionTicket.FilterOption.IS_ADMIN))) {
            return null;
        }
        KeycloakSession session = factory.session();
        RealmModel realm = session.getContext().getRealm();
        UserModel currentUser = session.getContext().getUser();
        if (currentUser == null || realm == null) {
            return null;
        }
        ClientModel resourceServerClient = session.clients().getClientById(realm, resourceServer.getClientId());
        if (resourceServerClient != null && resourceServerClient.isServiceAccountsEnabled()) {
            UserModel serviceAccount = session.users().getServiceAccount(resourceServerClient);
            if (serviceAccount != null && serviceAccount.equals(currentUser)) {
                return null;
            }
        }
        return currentUser.getId();
    }

    private boolean matchesFilters(PermissionTicketSpec spec,
                                   Map<PermissionTicket.FilterOption, String> attributes) {
        for (Map.Entry<PermissionTicket.FilterOption, String> filter : attributes.entrySet()) {
            String value = filter.getValue();
            boolean matches = switch (filter.getKey()) {
                case ID -> Objects.equals(value, spec.getId());
                case OWNER -> Objects.equals(value, spec.getOwner());
                case REQUESTER -> Objects.equals(value, spec.getRequester());
                case RESOURCE_ID -> Objects.equals(value, spec.getResourceId());
                case RESOURCE_NAME -> {
                    var resource = AuthzCrStore.resource(spec.getRealm(), spec.getResourceId());
                    yield resource != null && Objects.equals(value, resource.getName());
                }
                case SCOPE_ID -> Objects.equals(value, spec.getScopeId());
                case SCOPE_IS_NULL -> (spec.getScopeId() == null) == Boolean.parseBoolean(value);
                case GRANTED -> (spec.getGrantedTimestamp() != null) == Boolean.parseBoolean(value);
                case REQUESTER_IS_NULL -> spec.getRequester() == null;
                case POLICY_IS_NOT_NULL -> spec.getPolicyId() != null;
                case POLICY_ID -> Objects.equals(value, spec.getPolicyId());
                case IS_ADMIN -> true;
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<PermissionTicket> findGranted(ResourceServer resourceServer, String userId) {
        Map<PermissionTicket.FilterOption, String> filters = new EnumMap<>(PermissionTicket.FilterOption.class);
        filters.put(PermissionTicket.FilterOption.GRANTED, Boolean.TRUE.toString());
        filters.put(PermissionTicket.FilterOption.REQUESTER, userId);
        return find(resourceServer, filters, null, null);
    }

    @Override
    public List<PermissionTicket> findGranted(ResourceServer resourceServer, String resourceName, String userId) {
        Map<PermissionTicket.FilterOption, String> filters = new EnumMap<>(PermissionTicket.FilterOption.class);
        filters.put(PermissionTicket.FilterOption.RESOURCE_NAME, resourceName);
        filters.put(PermissionTicket.FilterOption.GRANTED, Boolean.TRUE.toString());
        filters.put(PermissionTicket.FilterOption.REQUESTER, userId);
        return find(resourceServer, filters, null, null);
    }

    @Override
    public List<Resource> findGrantedResources(String requester, String name, Integer firstResult,
                                               Integer maxResults) {
        Stream<String> resourceIds = specs(null)
                .filter(spec -> Objects.equals(requester, spec.getRequester())
                        && spec.getGrantedTimestamp() != null)
                .map(PermissionTicketSpec::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted();
        Stream<Resource> resources = resourceIds
                .map(id -> factory.resourceById(factory.contextRealmId(), id))
                .filter(Objects::nonNull)
                .map(Resource.class::cast);
        if (name != null) {
            // JPA parity: lower(name) like %term% - a plain case-insensitive contains
            resources = resources.filter(resource ->
                    LikePatterns.insensitiveLike(resource.getName(), "%" + name.toLowerCase() + "%"));
        }
        return paginatedStream(resources, firstResult, maxResults).toList();
    }

    @Override
    public List<Resource> findGrantedOwnerResources(String owner, Integer firstResult, Integer maxResults) {
        Stream<Resource> resources = specs(null)
                .filter(spec -> Objects.equals(owner, spec.getOwner()) && spec.getGrantedTimestamp() != null)
                .map(PermissionTicketSpec::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(id -> factory.resourceById(factory.contextRealmId(), id))
                .filter(Objects::nonNull)
                .map(Resource.class::cast);
        return paginatedStream(resources, firstResult, maxResults).toList();
    }
}
