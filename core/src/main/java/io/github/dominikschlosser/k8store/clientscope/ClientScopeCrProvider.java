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
package io.github.dominikschlosser.k8store.clientscope;

import static io.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_SCOPE_AFTER_REMOVE;
import static io.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_SCOPE_BEFORE_REMOVE;

import io.github.dominikschlosser.k8store.common.ScopeMappingSupport;
import io.github.dominikschlosser.k8store.crd.ClientScopeSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.ClientScopeProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * {@link ClientScopeProvider} serving client scopes from {@code KeycloakClientScope} custom
 * resources.
 *
 * <p>Identity convention (human-readable, GitOps-friendly): the scope id <em>is</em> the scope
 * name; explicitly supplied ids are not stored. Ids surface in admin URLs and in the clients'
 * assignment lists, so they read naturally in hand-authored CRs.
 */
public class ClientScopeCrProvider implements ClientScopeProvider {

    private static final Logger LOG = Logger.getLogger(ClientScopeCrProvider.class);

    private final KeycloakSession session;

    public ClientScopeCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private ClientScopeModel adapt(RealmModel realm, ClientScopeSpec spec) {
        return new ClientScopeAdapter(session, realm, spec);
    }

    private Stream<ClientScopeSpec> specs(RealmModel realm) {
        return ClientScopeCrStore.allInRealm(realm.getId()).stream();
    }

    @Override
    public Stream<ClientScopeModel> getClientScopesStream(RealmModel realm) {
        return specs(realm).map(spec -> adapt(realm, spec)).sorted(Comparator.comparing(ClientScopeModel::getName));
    }

    @Override
    public ClientScopeModel addClientScope(RealmModel realm, String id, String name) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        String scopeName = KeycloakModelUtils.convertClientScopeName(name);
        if (id != null && ClientScopeCrStore.exists(realm.getId(), id)) {
            throw new ModelDuplicateException("Client scope exists: " + id);
        }
        if (ClientScopeCrStore.exists(realm.getId(), scopeName)) {
            throw new ModelDuplicateException("Client scope with name '" + scopeName + "' in realm " + realm.getName());
        }
        if (id != null && !id.equals(scopeName)) {
            LOG.debugv(
                    "Ignoring requested client scope id {0}: this store uses the scope name {1} as id", id, scopeName);
        }

        ClientScopeSpec spec = new ClientScopeSpec();
        spec.setId(scopeName);
        spec.setName(scopeName);
        spec.setRealm(realm.getId());
        ClientScopeCrStore.save(spec);
        return adapt(realm, spec);
    }

    @Override
    public ClientScopeModel getClientScopeById(RealmModel realm, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ClientScopeSpec spec = ClientScopeCrStore.read(realm.getId(), id);
        return spec == null ? null : adapt(realm, spec);
    }

    @Override
    public boolean removeClientScope(RealmModel realm, String id) {
        if (id == null) {
            return false;
        }
        ClientScopeModel scope = getClientScopeById(realm, id);
        if (scope == null) {
            return false;
        }
        session.invalidate(CLIENT_SCOPE_BEFORE_REMOVE, realm, scope);
        ClientScopeCrStore.delete(realm.getId(), id);
        session.invalidate(CLIENT_SCOPE_AFTER_REMOVE, scope);
        return true;
    }

    @Override
    public void removeClientScopes(RealmModel realm) {
        getClientScopesStream(realm)
                .map(ClientScopeModel::getId)
                .collect(Collectors.toSet())
                .forEach(id -> removeClientScope(realm, id));
    }

    @Override
    public Stream<ClientScopeModel> getClientScopesByProtocol(RealmModel realm, String protocol) {
        if (protocol == null) {
            return null;
        }
        return specs(realm)
                .filter(spec -> Objects.equals(spec.getProtocol(), protocol))
                .map(spec -> adapt(realm, spec));
    }

    @Override
    public Stream<ClientScopeModel> getClientScopesByAttributes(
            RealmModel realm, Map<String, String> searchMap, boolean useOr) {
        if (searchMap == null || searchMap.isEmpty()) {
            return Stream.empty();
        }
        return specs(realm)
                .filter(spec -> {
                    Map<String, String> attributes = spec.getAttributes();
                    if (attributes == null || attributes.isEmpty()) {
                        return false;
                    }
                    Stream<Map.Entry<String, String>> criteria = searchMap.entrySet().stream();
                    return useOr
                            ? criteria.anyMatch(e -> Objects.equals(attributes.get(e.getKey()), e.getValue()))
                            : criteria.allMatch(e -> Objects.equals(attributes.get(e.getKey()), e.getValue()));
                })
                .map(spec -> adapt(realm, spec));
    }

    /** Realm removal: drop every client-scope CR of the realm. */
    void realmRemoved(RealmModel realm) {
        specs(realm).forEach(spec -> ClientScopeCrStore.delete(realm.getId(), spec.getName()));
    }

    /** Role removal cascade: purge the removed role from every scope's scope mappings. */
    void roleRemoved(RealmModel realm, RoleModel removed) {
        specs(realm).forEach(spec -> {
            if (ScopeMappingSupport.removeRole(spec, removed)) {
                LOG.tracef(
                        "Dropping removed role %s from scope mappings of client scope %s",
                        removed.getName(), spec.getName());
                ClientScopeCrStore.save(spec);
            }
        });
    }

    /** Role rename cascade: rewrite the renamed role in every scope's scope mappings. */
    void roleRenamed(RealmModel realm, RoleModel renamed, String newName) {
        specs(realm).forEach(spec -> {
            if (ScopeMappingSupport.renameRole(spec, renamed, newName)) {
                LOG.tracef(
                        "Rewriting renamed role %s to %s in scope mappings of client scope %s",
                        renamed.getName(), newName, spec.getName());
                ClientScopeCrStore.save(spec);
            }
        });
    }

    /**
     * Client-rename cascade: the client id keys the client section of every scope's scope
     * mappings. Rekey it from the old client id to the new one.
     */
    void clientRenamed(RealmModel realm, ClientModel renamed, String newClientId) {
        String oldClientId = renamed.getClientId();
        specs(realm).forEach(spec -> {
            Map<String, List<String>> byClient = spec.getClientScopeMappings();
            if (byClient == null) {
                return;
            }
            List<String> names = byClient.remove(oldClientId);
            if (names != null) {
                byClient.put(newClientId, names);
                ClientScopeCrStore.save(spec);
            }
        });
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
