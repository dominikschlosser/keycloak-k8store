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
package io.github.dominikschlosser.k8store.loginfailure;

import static io.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;

import com.google.auto.service.AutoService;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import io.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureProviderFactory;
import org.keycloak.models.UserModel;
import org.keycloak.provider.InvalidationHandler;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;

@AutoService(UserLoginFailureProviderFactory.class)
public class LoginFailureCrProviderFactory extends AbstractCrProviderFactory<LoginFailureCrProvider>
        implements UserLoginFailureProviderFactory<LoginFailureCrProvider>, InvalidationHandler {

    public LoginFailureCrProviderFactory() {
        super(LoginFailureCrProvider.class, K8sStoreConfig.Area.LOGIN_FAILURE);
    }

    @Override
    protected LoginFailureCrProvider createNew(KeycloakSession session) {
        return new LoginFailureCrProvider();
    }

    @Override
    public String getHelpText() {
        return "Login failure provider backed by KeycloakLoginFailure custom resources (experimental)";
    }

    /** Beats the stateless JPA default in {@code getProvider(Class)} resolution. */
    @Override
    public int order() {
        return DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1;
    }

    /** A removed user's brute-force counter CR must go with the user. */
    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof UserModel.UserRemovedEvent userRemoved) {
                create(userRemoved.getKeycloakSession())
                        .removeUserLoginFailure(
                                userRemoved.getRealm(), userRemoved.getUser().getId());
            }
        });
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        // login failures have no expiration; realm removal and user removal (postInit) clean up
        if (type == REALM_BEFORE_REMOVE) {
            create(session).removeAllUserLoginFailures((RealmModel) params[0]);
        }
    }
}
