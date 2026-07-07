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

import com.github.dominikschlosser.k8store.common.CrStore;
import com.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import com.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import com.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import com.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import com.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import java.util.List;

/**
 * Access to the five Authorization Services custom-resource kinds. Resource servers are keyed
 * by {@code (realm, clientId)}; resources, authorization scopes, policies and permission
 * tickets by {@code (realm, uuid)}. Reads come from the informer mirror and hand out defensive
 * copies; every mutation of a spec must be persisted explicitly through the matching
 * {@code save} - specs carry no write-through machinery. Whether a write is accepted in
 * read-only mode is decided per kind by the backend (permission tickets are always writable,
 * the other four kinds are config).
 */
final class AuthzCrStore {

    private static final CrStore<ResourceServerSpec> RESOURCE_SERVERS =
            new CrStore<>(ResourceServerSpec.class, ResourceServerSpec::getRealm, ResourceServerSpec::getClientId);
    private static final CrStore<AuthzResourceSpec> RESOURCES =
            new CrStore<>(AuthzResourceSpec.class, AuthzResourceSpec::getRealm, AuthzResourceSpec::getId);
    private static final CrStore<AuthzScopeSpec> SCOPES =
            new CrStore<>(AuthzScopeSpec.class, AuthzScopeSpec::getRealm, AuthzScopeSpec::getId);
    private static final CrStore<AuthzPolicySpec> POLICIES =
            new CrStore<>(AuthzPolicySpec.class, AuthzPolicySpec::getRealm, AuthzPolicySpec::getId);
    private static final CrStore<PermissionTicketSpec> TICKETS =
            new CrStore<>(PermissionTicketSpec.class, PermissionTicketSpec::getRealm, PermissionTicketSpec::getId);

    private AuthzCrStore() {}

    // ------------------------------------------------------------------ resource servers

    static ResourceServerSpec resourceServer(String realmId, String clientId) {
        return RESOURCE_SERVERS.read(realmId, clientId);
    }

    static List<ResourceServerSpec> resourceServers(String realmId) {
        return RESOURCE_SERVERS.allInRealm(realmId);
    }

    static List<ResourceServerSpec> resourceServersAnywhere() {
        return RESOURCE_SERVERS.readAll();
    }

    static ResourceServerSpec save(ResourceServerSpec spec) {
        return RESOURCE_SERVERS.save(spec);
    }

    static void deleteResourceServer(String realmId, String clientId) {
        RESOURCE_SERVERS.delete(realmId, clientId);
    }

    // ------------------------------------------------------------------ resources

    static AuthzResourceSpec resource(String realmId, String id) {
        return RESOURCES.read(realmId, id);
    }

    static List<AuthzResourceSpec> resources(String realmId) {
        return RESOURCES.allInRealm(realmId);
    }

    static List<AuthzResourceSpec> resourcesAnywhere() {
        return RESOURCES.readAll();
    }

    static AuthzResourceSpec save(AuthzResourceSpec spec) {
        return RESOURCES.save(spec);
    }

    static void deleteResource(String realmId, String id) {
        RESOURCES.delete(realmId, id);
    }

    // ------------------------------------------------------------------ authorization scopes

    static AuthzScopeSpec scope(String realmId, String id) {
        return SCOPES.read(realmId, id);
    }

    static List<AuthzScopeSpec> scopes(String realmId) {
        return SCOPES.allInRealm(realmId);
    }

    static List<AuthzScopeSpec> scopesAnywhere() {
        return SCOPES.readAll();
    }

    static AuthzScopeSpec save(AuthzScopeSpec spec) {
        return SCOPES.save(spec);
    }

    static void deleteScope(String realmId, String id) {
        SCOPES.delete(realmId, id);
    }

    // ------------------------------------------------------------------ policies

    static AuthzPolicySpec policy(String realmId, String id) {
        return POLICIES.read(realmId, id);
    }

    static List<AuthzPolicySpec> policies(String realmId) {
        return POLICIES.allInRealm(realmId);
    }

    static List<AuthzPolicySpec> policiesAnywhere() {
        return POLICIES.readAll();
    }

    static AuthzPolicySpec save(AuthzPolicySpec spec) {
        return POLICIES.save(spec);
    }

    static void deletePolicy(String realmId, String id) {
        POLICIES.delete(realmId, id);
    }

    // ------------------------------------------------------------------ permission tickets

    static PermissionTicketSpec ticket(String realmId, String id) {
        return TICKETS.read(realmId, id);
    }

    static List<PermissionTicketSpec> tickets(String realmId) {
        return TICKETS.allInRealm(realmId);
    }

    static List<PermissionTicketSpec> ticketsAnywhere() {
        return TICKETS.readAll();
    }

    static PermissionTicketSpec save(PermissionTicketSpec spec) {
        return TICKETS.save(spec);
    }

    static void deleteTicket(String realmId, String id) {
        TICKETS.delete(realmId, id);
    }
}
