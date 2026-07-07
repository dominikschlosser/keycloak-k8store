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

import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import com.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import com.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import com.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import com.github.dominikschlosser.k8store.crd.ResourceServerSpec;
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
 * clientIds and only unique per realm — the context realm wins for those).
 */
public class CrStoreFactory implements StoreFactory {

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
     * authorization scopes and finally the resource server itself — upstream JPA's
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
