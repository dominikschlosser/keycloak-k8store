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
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_RENAMED;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_SCOPE_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_SCOPE_RENAMED;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_RENAMED;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProviderFactory;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.provider.InvalidationHandler;

@AutoService(ClientProviderFactory.class)
public class ClientCrProviderFactory extends AbstractCrProviderFactory<ClientCrProvider>
        implements ClientProviderFactory<ClientCrProvider>, InvalidationHandler {

    /**
     * Cluster-node registrations per client id - runtime information that is deliberately kept
     * out of the custom resources.
     */
    private final Map<String, Map<String, Integer>> registeredNodesStore = new ConcurrentHashMap<>();

    public ClientCrProviderFactory() {
        super(ClientCrProvider.class, K8sStoreConfig.Area.CLIENT);
    }

    @Override
    protected ClientCrProvider createNew(KeycloakSession session) {
        return new ClientCrProvider(session, registeredNodesStore);
    }

    @Override
    public String getHelpText() {
        return "Client provider backed by KeycloakClient custom resources";
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == REALM_BEFORE_REMOVE) {
            create(session).realmRemoved((RealmModel) params[0]);
        } else if (type == ROLE_BEFORE_REMOVE) {
            create(session).roleRemoved((RealmModel) params[0], (RoleModel) params[1]);
        } else if (type == ROLE_RENAMED) {
            create(session).roleRenamed((RealmModel) params[0], (RoleModel) params[1], (String) params[2]);
        } else if (type == CLIENT_RENAMED) {
            create(session).clientRenamed((RealmModel) params[0], (ClientModel) params[1], (String) params[2]);
        } else if (type == CLIENT_SCOPE_BEFORE_REMOVE) {
            create(session).clientScopeRemoved((RealmModel) params[0], (ClientScopeModel) params[1]);
        } else if (type == CLIENT_SCOPE_RENAMED) {
            create(session).clientScopeRenamed((RealmModel) params[0], (String) params[1], (String) params[2]);
        } else if (type == CLIENT_AFTER_REMOVE) {
            session.getKeycloakSessionFactory().publish(new ClientModel.ClientRemovedEvent() {
                @Override
                public ClientModel getClient() {
                    return (ClientModel) params[0];
                }

                @Override
                public KeycloakSession getKeycloakSession() {
                    return session;
                }
            });
        }
    }
}
