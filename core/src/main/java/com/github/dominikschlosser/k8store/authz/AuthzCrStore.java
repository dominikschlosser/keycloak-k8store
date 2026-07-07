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
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
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

    private AuthzCrStore() {}

    // ------------------------------------------------------------------ resource servers

    static ResourceServerSpec resourceServer(String realmId, String clientId) {
        return K8sStorageBackend.get().read(ResourceServerSpec.class, realmId, clientId);
    }

    static List<ResourceServerSpec> resourceServers(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(ResourceServerSpec.class, realmId);
    }

    static List<ResourceServerSpec> resourceServersAnywhere() {
        return K8sStorageBackend.get().readAll(ResourceServerSpec.class);
    }

    static ResourceServerSpec save(ResourceServerSpec spec) {
        return K8sStorageBackend.update(ResourceServerSpec.class, spec.getRealm(), spec.getClientId(), spec);
    }

    static void deleteResourceServer(String realmId, String clientId) {
        K8sStorageBackend.delete(ResourceServerSpec.class, realmId, clientId);
    }

    // ------------------------------------------------------------------ resources

    static AuthzResourceSpec resource(String realmId, String id) {
        return K8sStorageBackend.get().read(AuthzResourceSpec.class, realmId, id);
    }

    static List<AuthzResourceSpec> resources(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(AuthzResourceSpec.class, realmId);
    }

    static List<AuthzResourceSpec> resourcesAnywhere() {
        return K8sStorageBackend.get().readAll(AuthzResourceSpec.class);
    }

    static AuthzResourceSpec save(AuthzResourceSpec spec) {
        return K8sStorageBackend.update(AuthzResourceSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    static void deleteResource(String realmId, String id) {
        K8sStorageBackend.delete(AuthzResourceSpec.class, realmId, id);
    }

    // ------------------------------------------------------------------ authorization scopes

    static AuthzScopeSpec scope(String realmId, String id) {
        return K8sStorageBackend.get().read(AuthzScopeSpec.class, realmId, id);
    }

    static List<AuthzScopeSpec> scopes(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(AuthzScopeSpec.class, realmId);
    }

    static List<AuthzScopeSpec> scopesAnywhere() {
        return K8sStorageBackend.get().readAll(AuthzScopeSpec.class);
    }

    static AuthzScopeSpec save(AuthzScopeSpec spec) {
        return K8sStorageBackend.update(AuthzScopeSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    static void deleteScope(String realmId, String id) {
        K8sStorageBackend.delete(AuthzScopeSpec.class, realmId, id);
    }

    // ------------------------------------------------------------------ policies

    static AuthzPolicySpec policy(String realmId, String id) {
        return K8sStorageBackend.get().read(AuthzPolicySpec.class, realmId, id);
    }

    static List<AuthzPolicySpec> policies(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(AuthzPolicySpec.class, realmId);
    }

    static List<AuthzPolicySpec> policiesAnywhere() {
        return K8sStorageBackend.get().readAll(AuthzPolicySpec.class);
    }

    static AuthzPolicySpec save(AuthzPolicySpec spec) {
        return K8sStorageBackend.update(AuthzPolicySpec.class, spec.getRealm(), spec.getId(), spec);
    }

    static void deletePolicy(String realmId, String id) {
        K8sStorageBackend.delete(AuthzPolicySpec.class, realmId, id);
    }

    // ------------------------------------------------------------------ permission tickets

    static PermissionTicketSpec ticket(String realmId, String id) {
        return K8sStorageBackend.get().read(PermissionTicketSpec.class, realmId, id);
    }

    static List<PermissionTicketSpec> tickets(String realmId) {
        return K8sStorageBackend.get().readAllInRealm(PermissionTicketSpec.class, realmId);
    }

    static List<PermissionTicketSpec> ticketsAnywhere() {
        return K8sStorageBackend.get().readAll(PermissionTicketSpec.class);
    }

    static PermissionTicketSpec save(PermissionTicketSpec spec) {
        return K8sStorageBackend.update(PermissionTicketSpec.class, spec.getRealm(), spec.getId(), spec);
    }

    static void deleteTicket(String realmId, String id) {
        K8sStorageBackend.delete(PermissionTicketSpec.class, realmId, id);
    }
}
