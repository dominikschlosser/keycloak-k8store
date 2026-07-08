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

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_RENAMED;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_RENAMED;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import org.keycloak.authorization.store.AuthorizationStoreFactory;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.provider.InvalidationHandler;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;

/**
 * Registers the Authorization Services store ({@code authorizationPersister} SPI, provider id
 * {@code k8store}) when the {@code authorization} area is enabled. Keycloak resolves the SPI's
 * default provider by the highest {@code order()}, so this factory outranks the built-in
 * {@code jpa} store (order 1); with the area disabled, {@code isSupported} prunes this factory
 * and authorization data stays on JPA.
 *
 * <p>Cleanup wiring, two layers:
 *
 * <ul>
 *   <li>k8store invalidation events: {@code CLIENT_BEFORE_REMOVE} deletes the removed client's
 *       whole authorization graph (tickets, policies, resources, scopes, resource server),
 *       {@code CLIENT_RENAMED} rewrites that same graph onto the new clientId (the resource
 *       server and its back-references are keyed by the clientId) and the clientId/client-role-id
 *       references embedded in policy config JSON (client policies' {@code clients} arrays and
 *       role policies' {@code roles} arrays), {@code ROLE_RENAMED} and
 *       {@code ROLE_BEFORE_REMOVE} rewrite or drop the role id inside every role policy's
 *       {@code roles} config (role ids encode the role name in this store), and
 *       {@code REALM_BEFORE_REMOVE} bulk-deletes every authorization CR of the realm - the
 *       realm path matters because the k8store realm removal deletes client CRs in bulk without
 *       per-client events, so upstream's realm synchronizer would find no clients left to
 *       cascade from.</li>
 *   <li>the SPI's standard synchronization listeners ({@code registerSynchronizationListeners}),
 *       kept for the cross-store cascades that operate above the store: user removal (drops the
 *       user's tickets and user-owned resources), client-policy purging on client removal, and
 *       the fine-grained-admin schema hooks. These run through the session's default store
 *       factory, so they compose with the graph deletes above (idempotent).</li>
 * </ul>
 *
 * <p>Note on read-only mode: the resource-server/resource/scope/policy kinds are configuration
 * and rejected by the backend in read-only mode, permission tickets stay writable. Fine-grained
 * admin permissions v2 writes policies at runtime - that feature requires write mode.
 */
@AutoService(AuthorizationStoreFactory.class)
public class AuthzCrStoreProviderFactory extends AbstractCrProviderFactory<StoreFactory>
        implements AuthorizationStoreFactory, InvalidationHandler {

    public AuthzCrStoreProviderFactory() {
        super(StoreFactory.class, K8sStoreConfig.Area.AUTHORIZATION);
    }

    @Override
    protected StoreFactory createNew(KeycloakSession session) {
        return new CrStoreFactory(session);
    }

    @Override
    public String getHelpText() {
        return "Authorization Services store backed by Keycloak*/KeycloakAuthz* custom resources";
    }

    /** Beats the built-in jpa authorization store (order 1) in default-provider resolution. */
    @Override
    public int order() {
        return DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1;
    }

    /**
     * {@link AbstractCrProviderFactory}'s no-op would shadow the SPI's default postInit - the
     * synchronization listeners must stay registered (see the class javadoc).
     */
    @Override
    public void postInit(KeycloakSessionFactory factory) {
        registerSynchronizationListeners(factory);
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == CLIENT_BEFORE_REMOVE) {
            ((CrStoreFactory) create(session)).clientRemoved((RealmModel) params[0], (ClientModel) params[1]);
        } else if (type == CLIENT_RENAMED) {
            ((CrStoreFactory) create(session))
                    .clientRenamed((RealmModel) params[0], (ClientModel) params[1], (String) params[2]);
        } else if (type == ROLE_RENAMED) {
            ((CrStoreFactory) create(session))
                    .roleRenamed((RealmModel) params[0], (RoleModel) params[1], (String) params[2]);
        } else if (type == ROLE_BEFORE_REMOVE) {
            ((CrStoreFactory) create(session)).roleRemoved((RealmModel) params[0], (RoleModel) params[1]);
        } else if (type == REALM_BEFORE_REMOVE) {
            ((CrStoreFactory) create(session)).realmRemoved((RealmModel) params[0]);
        }
    }
}
