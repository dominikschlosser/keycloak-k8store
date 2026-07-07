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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import com.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import com.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import com.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import com.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

/**
 * The {@code authorizationPersister} provider of the k8store datastore: Authorization Services
 * data served from the five authorization custom-resource kinds. One instance per session
 * (memoized by the factory); the {@code readOnly} flag is the SPI's per-instance model flag
 * (used e.g. by fine-grained admin permissions to hand out read-only views), unrelated to the
 * k8store {@code read-only} mode, which the backend enforces per kind at the write choke point.
 *
 * <p>Realm scoping: Keycloak's authorization SPI is realm-blind (upstream JPA ids are globally
 * unique), while this store keys every CR by realm. Lookups therefore resolve the realm from
 * the model instance where one is passed, else from the session context; the id-only lookups
 * fall back to a cross-realm mirror scan (ids are UUIDs except resource-server ids, which are
 * clientIds and only unique per realm - the context realm wins for those).
 */
public class CrStoreFactory implements StoreFactory {

    private static final Logger LOG = Logger.getLogger(CrStoreFactory.class);
    private static final ObjectMapper POLICY_JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ROLE_ENTRIES = new TypeReference<>() {};
    private static final TypeReference<List<String>> CLIENT_ENTRIES = new TypeReference<>() {};

    private final KeycloakSession session;
    private final CrResourceServerStore resourceServerStore;
    private final CrResourceStore resourceStore;
    private final CrScopeStore scopeStore;
    private final CrPolicyStore policyStore;
    private final CrPermissionTicketStore permissionTicketStore;
    private boolean readOnly;

    public CrStoreFactory(KeycloakSession session) {
        this.session = session;
        this.resourceServerStore = new CrResourceServerStore(this);
        this.resourceStore = new CrResourceStore(this);
        this.scopeStore = new CrScopeStore(this);
        this.policyStore = new CrPolicyStore(this);
        this.permissionTicketStore = new CrPermissionTicketStore(this);
    }

    @Override
    public ResourceServerStore getResourceServerStore() {
        return resourceServerStore;
    }

    @Override
    public ResourceStore getResourceStore() {
        return resourceStore;
    }

    @Override
    public ScopeStore getScopeStore() {
        return scopeStore;
    }

    @Override
    public PolicyStore getPolicyStore() {
        return policyStore;
    }

    @Override
    public PermissionTicketStore getPermissionTicketStore() {
        return permissionTicketStore;
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void close() {}

    KeycloakSession session() {
        return session;
    }

    // ------------------------------------------------------------------ realm resolution

    /** Realm id of the session context, or null outside a realm-scoped request. */
    String contextRealmId() {
        RealmModel realm = session.getContext().getRealm();
        return realm == null ? null : realm.getId();
    }

    /** Realm id a store call is scoped to: the model's own realm where known, else the context. */
    String realmOf(ResourceServer resourceServer) {
        if (resourceServer instanceof ResourceServerAdapter adapter) {
            return adapter.getRealmId();
        }
        return contextRealmId();
    }

    // ------------------------------------------------------------------ adapter lookups

    ResourceServerAdapter wrap(ResourceServerSpec spec) {
        return spec == null ? null : new ResourceServerAdapter(this, spec);
    }

    ResourceAdapter wrap(AuthzResourceSpec spec) {
        return spec == null ? null : new ResourceAdapter(this, spec);
    }

    ScopeAdapter wrap(AuthzScopeSpec spec) {
        return spec == null ? null : new ScopeAdapter(this, spec);
    }

    PolicyAdapter wrap(AuthzPolicySpec spec) {
        return spec == null ? null : new PolicyAdapter(this, spec);
    }

    PermissionTicketAdapter wrap(PermissionTicketSpec spec) {
        return spec == null ? null : new PermissionTicketAdapter(this, spec);
    }

    ResourceServerAdapter resourceServerById(String realmId, String clientId) {
        if (realmId != null) {
            return wrap(AuthzCrStore.resourceServer(realmId, clientId));
        }
        return AuthzCrStore.resourceServersAnywhere().stream()
                .filter(spec -> clientId != null && clientId.equals(spec.getClientId()))
                .findFirst()
                .map(this::wrap)
                .orElse(null);
    }

    ResourceAdapter resourceById(String realmId, String id) {
        if (realmId != null) {
            AuthzResourceSpec spec = AuthzCrStore.resource(realmId, id);
            if (spec != null) {
                return wrap(spec);
            }
        }
        // UUID ids never collide across realms, so the cross-realm fallback is unambiguous
        return AuthzCrStore.resourcesAnywhere().stream()
                .filter(spec -> id != null && id.equals(spec.getId()))
                .findFirst()
                .map(this::wrap)
                .orElse(null);
    }

    ScopeAdapter scopeById(String realmId, String id) {
        if (realmId != null) {
            AuthzScopeSpec spec = AuthzCrStore.scope(realmId, id);
            if (spec != null) {
                return wrap(spec);
            }
        }
        return AuthzCrStore.scopesAnywhere().stream()
                .filter(spec -> id != null && id.equals(spec.getId()))
                .findFirst()
                .map(this::wrap)
                .orElse(null);
    }

    PolicyAdapter policyById(String realmId, String id) {
        if (realmId != null) {
            AuthzPolicySpec spec = AuthzCrStore.policy(realmId, id);
            if (spec != null) {
                return wrap(spec);
            }
        }
        return AuthzCrStore.policiesAnywhere().stream()
                .filter(spec -> id != null && id.equals(spec.getId()))
                .findFirst()
                .map(this::wrap)
                .orElse(null);
    }

    PermissionTicketAdapter ticketById(String realmId, String id) {
        if (realmId != null) {
            PermissionTicketSpec spec = AuthzCrStore.ticket(realmId, id);
            if (spec != null) {
                return wrap(spec);
            }
        }
        return AuthzCrStore.ticketsAnywhere().stream()
                .filter(spec -> id != null && id.equals(spec.getId()))
                .findFirst()
                .map(this::wrap)
                .orElse(null);
    }

    // ------------------------------------------------------------------ cascades

    /**
     * Deletes one client's whole authorization graph: permission tickets, policies, resources,
     * authorization scopes and finally the resource server itself - upstream JPA's
     * resource-server delete cascade over CRs. Policies are plain CRs whose associations are id
     * sets inside the graph being deleted, so no reference rewriting is needed.
     */
    void deleteResourceServerGraph(String realmId, String clientId) {
        if (realmId == null || clientId == null) {
            return;
        }
        AuthzCrStore.tickets(realmId).stream()
                .filter(spec -> clientId.equals(spec.getResourceServer()))
                .forEach(spec -> AuthzCrStore.deleteTicket(realmId, spec.getId()));
        AuthzCrStore.policies(realmId).stream()
                .filter(spec -> clientId.equals(spec.getResourceServer()))
                .forEach(spec -> AuthzCrStore.deletePolicy(realmId, spec.getId()));
        AuthzCrStore.resources(realmId).stream()
                .filter(spec -> clientId.equals(spec.getResourceServer()))
                .forEach(spec -> AuthzCrStore.deleteResource(realmId, spec.getId()));
        AuthzCrStore.scopes(realmId).stream()
                .filter(spec -> clientId.equals(spec.getResourceServer()))
                .forEach(spec -> AuthzCrStore.deleteScope(realmId, spec.getId()));
        if (AuthzCrStore.resourceServer(realmId, clientId) != null) {
            AuthzCrStore.deleteResourceServer(realmId, clientId);
        }
    }

    /** Client removal cascade (k8store {@code CLIENT_BEFORE_REMOVE}). */
    void clientRemoved(RealmModel realm, ClientModel client) {
        deleteResourceServerGraph(realm.getId(), client.getId());
    }

    /**
     * Client-rename cascade (k8store {@code CLIENT_RENAMED}). Two kinds of reference break when a
     * clientId changes (client id = clientId in this store):
     *
     * <ul>
     *   <li>policy config JSON - client policies list client ids in their {@code clients} array,
     *       and role policies list this client's role ids ({@code <clientId>:<name>}) in their
     *       {@code roles} array; both are rewritten across every policy in the realm (any client
     *       can be referenced, not only one that is itself a resource server);</li>
     *   <li>the resource server keyed by this clientId and its graph's {@code resourceServer}
     *       back-references - rewritten and moved to the new clientId, the rewrite mirror of
     *       {@link #deleteResourceServerGraph}.</li>
     * </ul>
     */
    void clientRenamed(RealmModel realm, ClientModel client, String newClientId) {
        String realmId = realm.getId();
        String oldClientId = client.getId();
        if (realmId == null || oldClientId == null || newClientId == null) {
            return;
        }
        rewriteClientInPolicies(realmId, oldClientId, newClientId);
        ResourceServerSpec resourceServer = AuthzCrStore.resourceServer(realmId, oldClientId);
        if (resourceServer == null) {
            return;
        }
        AuthzCrStore.tickets(realmId).stream()
                .filter(spec -> oldClientId.equals(spec.getResourceServer()))
                .forEach(spec -> {
                    spec.setResourceServer(newClientId);
                    AuthzCrStore.save(spec);
                });
        AuthzCrStore.policies(realmId).stream()
                .filter(spec -> oldClientId.equals(spec.getResourceServer()))
                .forEach(spec -> {
                    spec.setResourceServer(newClientId);
                    AuthzCrStore.save(spec);
                });
        AuthzCrStore.resources(realmId).stream()
                .filter(spec -> oldClientId.equals(spec.getResourceServer()))
                .forEach(spec -> {
                    spec.setResourceServer(newClientId);
                    AuthzCrStore.save(spec);
                });
        AuthzCrStore.scopes(realmId).stream()
                .filter(spec -> oldClientId.equals(spec.getResourceServer()))
                .forEach(spec -> {
                    spec.setResourceServer(newClientId);
                    AuthzCrStore.save(spec);
                });
        resourceServer.setClientId(newClientId);
        AuthzCrStore.deleteResourceServer(realmId, oldClientId);
        AuthzCrStore.save(resourceServer);
    }

    /**
     * Role-rename cascade (k8store {@code ROLE_RENAMED}): role policies store role ids in their
     * {@code roles} config JSON, and this store's role ids encode the name (realm role id = name,
     * client role id = clientId:name), so a rename changes them. Rewrite the old role id to the
     * new one in every role policy of the realm.
     */
    void roleRenamed(RealmModel realm, RoleModel role, String newName) {
        String newRoleId = role.isClientRole() ? role.getContainerId() + ":" + newName : newName;
        rewriteRoleInPolicies(realm.getId(), role.getId(), newRoleId);
    }

    /**
     * Role-removal cascade (k8store {@code ROLE_BEFORE_REMOVE}): drop the removed role's id from
     * every role policy's {@code roles} config, so no policy is left referencing a role that no
     * longer exists.
     */
    void roleRemoved(RealmModel realm, RoleModel role) {
        rewriteRoleInPolicies(realm.getId(), role.getId(), null);
    }

    /**
     * Rewrites a role id inside every role policy's {@code roles} config array in the realm, or
     * removes the entry when {@code newRoleId} is null.
     */
    private void rewriteRoleInPolicies(String realmId, String oldRoleId, String newRoleId) {
        if (realmId == null || oldRoleId == null) {
            return;
        }
        for (AuthzPolicySpec policy : AuthzCrStore.policies(realmId)) {
            Map<String, String> config = policy.getConfig();
            List<Map<String, Object>> roles = parseConfigList(config, "roles", ROLE_ENTRIES, policy.getId());
            if (roles == null) {
                continue;
            }
            boolean changed = false;
            for (Iterator<Map<String, Object>> it = roles.iterator(); it.hasNext();) {
                Map<String, Object> entry = it.next();
                if (oldRoleId.equals(entry.get("id"))) {
                    if (newRoleId == null) {
                        it.remove();
                    } else {
                        entry.put("id", newRoleId);
                    }
                    changed = true;
                }
            }
            if (changed && writeConfigList(config, "roles", roles, policy.getId())) {
                AuthzCrStore.save(policy);
            }
        }
    }

    /**
     * Rewrites client-id references inside every policy's config in the realm: the {@code clients}
     * array of client policies (a client id equals the clientId here) and, for the renamed
     * client's own roles, the {@code <clientId>:<name>} ids in role policies' {@code roles} array.
     */
    private void rewriteClientInPolicies(String realmId, String oldClientId, String newClientId) {
        String oldRolePrefix = oldClientId + ":";
        for (AuthzPolicySpec policy : AuthzCrStore.policies(realmId)) {
            Map<String, String> config = policy.getConfig();
            boolean changed = false;

            List<String> clients = parseConfigList(config, "clients", CLIENT_ENTRIES, policy.getId());
            if (clients != null) {
                boolean rewritten = false;
                for (int i = 0; i < clients.size(); i++) {
                    if (oldClientId.equals(clients.get(i))) {
                        clients.set(i, newClientId);
                        rewritten = true;
                    }
                }
                changed |= rewritten && writeConfigList(config, "clients", clients, policy.getId());
            }

            List<Map<String, Object>> roles = parseConfigList(config, "roles", ROLE_ENTRIES, policy.getId());
            if (roles != null) {
                boolean rewritten = false;
                for (Map<String, Object> entry : roles) {
                    if (entry.get("id") instanceof String id && id.startsWith(oldRolePrefix)) {
                        entry.put("id", newClientId + ":" + id.substring(oldRolePrefix.length()));
                        rewritten = true;
                    }
                }
                changed |= rewritten && writeConfigList(config, "roles", roles, policy.getId());
            }

            if (changed) {
                AuthzCrStore.save(policy);
            }
        }
    }

    /** Parses a JSON-array policy config value, or null when absent, blank or unparseable. */
    private <T> T parseConfigList(Map<String, String> config, String key, TypeReference<T> type, String policyId) {
        String json = config == null ? null : config.get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return POLICY_JSON.readValue(json, type);
        } catch (IOException e) {
            LOG.warnv(e, "k8store: could not parse the {0} config of authorization policy {1}; skipping its"
                    + " rename rewrite", key, policyId);
            return null;
        }
    }

    /** Serializes a rewritten policy config value back into the config map; false on failure. */
    private boolean writeConfigList(Map<String, String> config, String key, Object value, String policyId) {
        try {
            config.put(key, POLICY_JSON.writeValueAsString(value));
            return true;
        } catch (IOException e) {
            LOG.warnv(e, "k8store: could not re-serialize the {0} config of authorization policy {1}",
                    key, policyId);
            return false;
        }
    }

    /** Realm removal cascade (k8store {@code REALM_BEFORE_REMOVE}): drop every authz CR. */
    void realmRemoved(RealmModel realm) {
        String realmId = realm.getId();
        AuthzCrStore.tickets(realmId).forEach(spec -> AuthzCrStore.deleteTicket(realmId, spec.getId()));
        AuthzCrStore.policies(realmId).forEach(spec -> AuthzCrStore.deletePolicy(realmId, spec.getId()));
        AuthzCrStore.resources(realmId).forEach(spec -> AuthzCrStore.deleteResource(realmId, spec.getId()));
        AuthzCrStore.scopes(realmId).forEach(spec -> AuthzCrStore.deleteScope(realmId, spec.getId()));
        AuthzCrStore.resourceServers(realmId)
                .forEach(spec -> AuthzCrStore.deleteResourceServer(realmId, spec.getClientId()));
    }
}
