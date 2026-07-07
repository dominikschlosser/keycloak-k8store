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
package com.github.dominikschlosser.k8store.role;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_AFTER_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_BEFORE_REMOVE;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.RoleProviderFactory;
import org.keycloak.provider.InvalidationHandler;

@AutoService(RoleProviderFactory.class)
public class RoleCrProviderFactory extends AbstractCrProviderFactory<RoleCrProvider>
        implements RoleProviderFactory<RoleCrProvider>, InvalidationHandler {

    public RoleCrProviderFactory() {
        super(RoleCrProvider.class, K8sStoreConfig.Area.ROLE);
    }

    @Override
    protected RoleCrProvider createNew(KeycloakSession session) {
        return new RoleCrProvider(session);
    }

    @Override
    public String getHelpText() {
        return "Role provider backed by KeycloakRole custom resources";
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == REALM_BEFORE_REMOVE) {
            create(session).realmRemoved((RealmModel) params[0]);
        } else if (type == CLIENT_BEFORE_REMOVE) {
            create(session).removeRoles((ClientModel) params[1]);
        } else if (type == ROLE_BEFORE_REMOVE) {
            create(session).roleRemoved((RealmModel) params[0], (RoleModel) params[1]);
        } else if (type == ROLE_AFTER_REMOVE) {
            session.getKeycloakSessionFactory().publish(new RoleContainerModel.RoleRemovedEvent() {
                @Override
                public RoleModel getRole() {
                    return (RoleModel) params[1];
                }

                @Override
                public KeycloakSession getKeycloakSession() {
                    return session;
                }
            });
        }
    }
}
