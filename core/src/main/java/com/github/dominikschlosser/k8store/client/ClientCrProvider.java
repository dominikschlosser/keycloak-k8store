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
package com.github.dominikschlosser.k8store.client;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_AFTER_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_BEFORE_REMOVE;
import static org.keycloak.utils.StreamsUtil.paginatedStream;

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.common.ProtocolMapperSupport;
import com.github.dominikschlosser.k8store.common.ScopeMappingSupport;
import com.github.dominikschlosser.k8store.crd.ClientSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

/**
 * {@link ClientProvider} serving clients from {@code KeycloakClient} custom resources.
 *
 * <p>Identity convention (human-readable, GitOps-friendly): the client id <em>is</em> the
 * clientId — {@code getClientById} and {@code getClientByClientId} resolve the same key.
 * Default/optional client-scope assignments are stored as scope <em>names</em> in the
 * representation's standard {@code defaultClientScopes}/{@code optionalClientScopes} lists.
 */
public class ClientCrProvider implements ClientProvider {

    private static final Logger LOG = Logger.getLogger(ClientCrProvider.class);

    private final KeycloakSession session;
    private final Map<String, Map<String, Integer>> registeredNodesStore;

    public ClientCrProvider(KeycloakSession session, Map<String, Map<String, Integer>> registeredNodesStore) {
        this.session = session;
        this.registeredNodesStore = registeredNodesStore;
    }

    private ClientModel adapt(RealmModel realm, ClientSpec spec) {
        return new ClientAdapter(session, realm, spec, registeredNodesStore);
    }

    private Stream<ClientSpec> specs(RealmModel realm) {
        return ClientCrStore.allInRealm(realm.getId()).stream();
    }

    // ------------------------------------------------------------------ create

    @Override
    public ClientModel addClient(RealmModel realm, String id, String clientId) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId cannot be null");
        }
        if (id != null && ClientCrStore.exists(realm.getId(), id)) {
            throw new ModelDuplicateException("Client with same id exists: " + id);
        }
        if (getClientByClientId(realm, clientId) != null) {
            throw new ModelDuplicateException(
                    "Client with same clientId in realm " + realm.getName() + " exists: " + clientId);
        }

        ClientSpec spec = new ClientSpec();
        spec.setId(clientId);
        spec.setClientId(clientId);
        spec.setRealm(realm.getId());
        spec.setEnabled(true);
        spec.setStandardFlowEnabled(true);
        ClientCrStore.save(spec);

        ClientModel client = adapt(realm, spec);
        session.getKeycloakSessionFactory().publish((ClientModel.ClientCreationEvent) () -> client);
        client.updateClient();
        return client;
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public ClientModel getClientById(RealmModel realm, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ClientSpec spec = ClientCrStore.read(realm.getId(), id);
        return spec == null ? null : adapt(realm, spec);
    }

    @Override
    public ClientModel getClientByClientId(RealmModel realm, String clientId) {
        // the store id is the clientId
        return getClientById(realm, clientId);
    }

    @Override
    public Stream<ClientModel> getClientsStream(RealmModel realm) {
        return specs(realm)
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(ClientModel::getClientId));
    }

    @Override
    public Stream<ClientModel> getClientsStream(RealmModel realm, Integer firstResult, Integer maxResults) {
        return paginatedStream(getClientsStream(realm), firstResult, maxResults);
    }

    @Override
    public Stream<ClientModel> getAlwaysDisplayInConsoleClientsStream(RealmModel realm) {
        return specs(realm)
                .filter(spec -> Boolean.TRUE.equals(spec.isAlwaysDisplayInConsole()))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(ClientModel::getClientId));
    }

    @Override
    public long getClientsCount(RealmModel realm) {
        return ClientCrStore.allInRealm(realm.getId()).size();
    }

    // ------------------------------------------------------------------ search

    @Override
    public Stream<ClientModel> searchClientsByClientIdStream(
            RealmModel realm, String clientId, Integer firstResult, Integer maxResults) {
        if (clientId == null) {
            return Stream.empty();
        }
        String pattern = "%" + clientId + "%";
        Stream<ClientModel> clients = specs(realm)
                .filter(spec -> LikePatterns.insensitiveLike(spec.getClientId(), pattern))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(ClientModel::getClientId));
        return paginatedStream(clients, firstResult, maxResults);
    }

    @Override
    public Stream<ClientModel> searchClientsByAttributes(
            RealmModel realm, Map<String, String> attributes, Integer firstResult, Integer maxResults) {
        Stream<ClientModel> clients = specs(realm)
                .filter(spec -> attributes.entrySet().stream().allMatch(entry -> spec.getAttributes() != null
                        && Objects.equals(spec.getAttributes().get(entry.getKey()), entry.getValue())))
                .map(spec -> adapt(realm, spec))
                .sorted(Comparator.comparing(ClientModel::getClientId));
        return paginatedStream(clients, firstResult, maxResults);
    }

    // ------------------------------------------------------------------ removal

    @Override
    public boolean removeClient(RealmModel realm, String id) {
        if (id == null) {
            return false;
        }
        ClientModel client = getClientById(realm, id);
        if (client == null) {
            return false;
        }
        session.invalidate(CLIENT_BEFORE_REMOVE, realm, client);
        ClientCrStore.delete(realm.getId(), id);
        registeredNodesStore.remove(id);
        session.invalidate(CLIENT_AFTER_REMOVE, client);
        return true;
    }

    @Override
    public void removeClients(RealmModel realm) {
        getClientsStream(realm)
                .map(ClientModel::getId)
                .collect(Collectors.toSet())
                .forEach(id -> removeClient(realm, id));
    }

    /** Realm removal: drop every client CR of the realm without per-client events. */
    void realmRemoved(RealmModel realm) {
        specs(realm).forEach(spec -> ClientCrStore.delete(realm.getId(), spec.getClientId()));
    }

    /** Role removal cascade: purge the removed role from every client's scope mappings. */
    void roleRemoved(RealmModel realm, RoleModel removed) {
        specs(realm).forEach(spec -> {
            if (ScopeMappingSupport.removeRole(spec, removed)) {
                LOG.tracef("Dropping removed role %s from scope mappings of client %s",
                        removed.getName(), spec.getClientId());
                ClientCrStore.save(spec);
            }
        });
    }

    /** Client-scope removal cascade: purge the scope from every client's assignment lists. */
    void clientScopeRemoved(RealmModel realm, ClientScopeModel scope) {
        specs(realm).forEach(spec -> {
            boolean changed = spec.getDefaultClientScopes() != null
                    && spec.getDefaultClientScopes().remove(scope.getName());
            changed |= spec.getOptionalClientScopes() != null
                    && spec.getOptionalClientScopes().remove(scope.getName());
            if (changed) {
                ClientCrStore.save(spec);
            }
        });
    }

    // ------------------------------------------------------------------ client scope assignment

    /**
     * The spec to mutate for a scope-assignment write against {@code client}: the adapter's own
     * live instance when the caller holds one. A client being created persists its spec after
     * every property update, so mutating a freshly read copy instead would be clobbered by the
     * adapter's next persist — observed with the default-client-scope assignment that the
     * login-protocol factories perform from the {@code ClientProtocolUpdatedEvent} fired in the
     * middle of client creation ({@code RepresentationToModel.updateClientProperties}).
     */
    private ClientSpec liveSpec(RealmModel realm, ClientModel client) {
        if (client instanceof ClientAdapter adapter) {
            return adapter.spec();
        }
        return ClientCrStore.read(realm.getId(), client.getId());
    }

    @Override
    public void addClientScopes(
            RealmModel realm, ClientModel client, Set<ClientScopeModel> clientScopes, boolean defaultScope) {
        ClientSpec spec = liveSpec(realm, client);
        if (spec == null) {
            return;
        }
        String clientProtocol = ProtocolMapperSupport.effectiveProtocol(spec);
        Set<String> assigned = new HashSet<>();
        if (spec.getDefaultClientScopes() != null) {
            assigned.addAll(spec.getDefaultClientScopes());
        }
        if (spec.getOptionalClientScopes() != null) {
            assigned.addAll(spec.getOptionalClientScopes());
        }
        List<String> target = defaultScope ? spec.getDefaultClientScopes() : spec.getOptionalClientScopes();
        if (target == null) {
            target = new ArrayList<>();
            if (defaultScope) {
                spec.setDefaultClientScopes(target);
            } else {
                spec.setOptionalClientScopes(target);
            }
        }
        boolean changed = false;
        for (ClientScopeModel clientScope : clientScopes) {
            if (assigned.contains(clientScope.getName())
                    || !Objects.equals(clientScope.getProtocol(), clientProtocol)) {
                continue;
            }
            target.add(clientScope.getName());
            changed = true;
        }
        if (changed) {
            ClientCrStore.save(spec);
        }
    }

    @Override
    public void removeClientScope(RealmModel realm, ClientModel client, ClientScopeModel clientScope) {
        if (client == null || clientScope == null) {
            return;
        }
        ClientSpec spec = liveSpec(realm, client);
        if (spec == null) {
            return;
        }
        boolean changed = spec.getDefaultClientScopes() != null
                && spec.getDefaultClientScopes().remove(clientScope.getName());
        changed |= spec.getOptionalClientScopes() != null
                && spec.getOptionalClientScopes().remove(clientScope.getName());
        if (changed) {
            ClientCrStore.save(spec);
        }
    }

    @Override
    public void addClientScopeToAllClients(RealmModel realm, ClientScopeModel clientScope, boolean defaultClientScope) {
        getClientsStream(realm).forEach(client ->
                addClientScopes(realm, client, Set.of(clientScope), defaultClientScope));
    }

    @Override
    public Map<String, ClientScopeModel> getClientScopes(RealmModel realm, ClientModel client, boolean defaultScopes) {
        ClientSpec spec = ClientCrStore.read(realm.getId(), client.getId());
        if (spec == null) {
            return null;
        }
        List<String> names = defaultScopes ? spec.getDefaultClientScopes() : spec.getOptionalClientScopes();
        if (names == null || names.isEmpty()) {
            return new HashMap<>();
        }
        String clientProtocol = ProtocolMapperSupport.effectiveProtocol(spec);
        Map<String, ClientScopeModel> byName = session.clientScopes().getClientScopesStream(realm)
                .collect(Collectors.toMap(ClientScopeModel::getName, Function.identity(), (a, b) -> a));
        Map<String, ClientScopeModel> result = new LinkedHashMap<>();
        for (String name : names) {
            ClientScopeModel scope = byName.get(name);
            if (scope != null && Objects.equals(scope.getProtocol(), clientProtocol)) {
                result.put(name, scope);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ misc

    /**
     * @deprecated mirrors the interface's deprecation: only used by a deprecated logout endpoint.
     */
    @Deprecated
    @Override
    public Map<ClientModel, Set<String>> getAllRedirectUrisOfEnabledClients(RealmModel realm) {
        return specs(realm)
                .filter(spec -> Boolean.TRUE.equals(spec.isEnabled()))
                .filter(spec -> spec.getRedirectUris() != null && !spec.getRedirectUris().isEmpty())
                .collect(Collectors.toMap(spec -> adapt(realm, spec), spec -> new HashSet<>(spec.getRedirectUris())));
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
