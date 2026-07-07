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
package com.github.dominikschlosser.k8store.usersession;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_RENAMED;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionProviderFactory;
import org.keycloak.provider.InvalidationHandler;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;

@AutoService(UserSessionProviderFactory.class)
public class UserSessionCrProviderFactory extends AbstractCrProviderFactory<UserSessionCrProvider>
        implements UserSessionProviderFactory<UserSessionCrProvider>, InvalidationHandler {

    public UserSessionCrProviderFactory() {
        super(UserSessionCrProvider.class, K8sStoreConfig.Area.USER_SESSION);
    }

    @Override
    protected UserSessionCrProvider createNew(KeycloakSession session) {
        return new UserSessionCrProvider(session);
    }

    @Override
    public String getHelpText() {
        return "User session provider backed by KeycloakUserSession custom resources (experimental)";
    }

    /**
     * A removed user's sessions must not survive as CRs until they expire - upstream's session
     * providers listen for the user removal the same way. Both online and offline sessions are
     * removed.
     */
    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof UserModel.UserRemovedEvent userRemoved) {
                create(userRemoved.getKeycloakSession())
                        .onUserRemoved(userRemoved.getRealm(), userRemoved.getUser());
            }
        });
    }

    /**
     * The datastore delegation by explicit provider id is the primary resolution path; the
     * order additionally wins any {@code getProvider(Class)} default resolution against the
     * built-in session factories (the infinispan/persistent factory registers with order 1).
     */
    @Override
    public int order() {
        return DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1;
    }

    /**
     * A clientId rename must rekey embedded client sessions (keyed by clientId), otherwise the
     * renamed client's sessions orphan. The broadcasting adapter still reports the old clientId,
     * so {@code params[1].getId()} is the old key and {@code params[2]} the new one.
     */
    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == CLIENT_RENAMED) {
            create(session).onClientRenamed(
                    (RealmModel) params[0], ((ClientModel) params[1]).getId(), (String) params[2]);
        }
    }
}
