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
package com.github.dominikschlosser.k8store.realm;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_AFTER_REMOVE;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProviderFactory;
import org.keycloak.provider.InvalidationHandler;

@AutoService(RealmProviderFactory.class)
public class RealmCrProviderFactory extends AbstractCrProviderFactory<RealmCrProvider>
        implements RealmProviderFactory<RealmCrProvider>, InvalidationHandler {

    public RealmCrProviderFactory() {
        super(RealmCrProvider.class, K8sStoreConfig.Area.REALM);
    }

    @Override
    protected RealmCrProvider createNew(KeycloakSession session) {
        return new RealmCrProvider(session);
    }

    @Override
    public String getHelpText() {
        return "Realm provider backed by KeycloakRealm custom resources";
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == REALM_AFTER_REMOVE) {
            session.getKeycloakSessionFactory().publish(new RealmModel.RealmRemovedEvent() {
                @Override
                public RealmModel getRealm() {
                    return (RealmModel) params[0];
                }

                @Override
                public KeycloakSession getKeycloakSession() {
                    return session;
                }
            });
        }
    }
}
