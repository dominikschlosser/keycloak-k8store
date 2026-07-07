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
package com.github.dominikschlosser.k8store.user;

import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.CLIENT_SCOPE_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.GROUP_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.REALM_BEFORE_REMOVE;
import static com.github.dominikschlosser.k8store.spi.StoreInvalidation.ROLE_BEFORE_REMOVE;

import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import com.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import com.google.auto.service.AutoService;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserProviderFactory;
import org.keycloak.provider.InvalidationHandler;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;

/**
 * Factory of the CR-backed user provider (experimental {@code user} area).
 *
 * <p>User-storage federation (LDAP/Kerberos components) is supported: the datastore routes
 * {@code session.users()} through Keycloak's {@code UserStorageManager} with
 * {@link UserCrProvider} as its local storage, so federation providers are consulted exactly
 * as with the JPA store and imported federated users become user CRs carrying
 * {@code spec.federationLink}.
 *
 * <p>OID4VC verifiable credentials (experimental {@code oid4vc-vci} feature) are stored in two
 * dedicated CR kinds ({@code KeycloakUserVerifiableCredential},
 * {@code KeycloakIssuedVerifiableCredential}) registered only when the feature is enabled
 * together with the area; with the feature disabled the lookups answer empty/null, the
 * removals are no-ops and the writes throw.
 */
@AutoService(UserProviderFactory.class)
public class UserCrProviderFactory extends AbstractCrProviderFactory<UserCrProvider>
        implements UserProviderFactory<UserCrProvider>, InvalidationHandler {

    public UserCrProviderFactory() {
        super(UserCrProvider.class, K8sStoreConfig.Area.USER);
    }

    @Override
    protected UserCrProvider createNew(KeycloakSession session) {
        return new UserCrProvider(session);
    }

    @Override
    public String getHelpText() {
        return "User provider backed by KeycloakUser custom resources (experimental)";
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == REALM_BEFORE_REMOVE) {
            create(session).realmRemoved((RealmModel) params[0]);
        } else if (type == ROLE_BEFORE_REMOVE) {
            create(session).roleRemoved((RealmModel) params[0], (RoleModel) params[1]);
        } else if (type == GROUP_BEFORE_REMOVE) {
            create(session).groupRemoved((RealmModel) params[0], (GroupModel) params[1]);
        } else if (type == CLIENT_BEFORE_REMOVE) {
            create(session).clientRemoved((RealmModel) params[0], (ClientModel) params[1]);
        } else if (type == CLIENT_SCOPE_BEFORE_REMOVE) {
            create(session).clientScopeRemoved((RealmModel) params[0], (ClientScopeModel) params[1]);
        }
    }

    /**
     * Identity-provider removal is announced as a model event (the realm adapter publishes it),
     * not through the store invalidations - listen for it to drop the removed alias's federated
     * identity links and broker tokens from the user CRs.
     */
    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof RealmModel.IdentityProviderRemovedEvent idpRemoved) {
                create(idpRemoved.getKeycloakSession()).identityProviderRemoved(
                        idpRemoved.getRealm(), idpRemoved.getRemovedIdentityProvider().getAlias());
            }
        });
    }

    /**
     * The datastore delegation by explicit provider id is the primary resolution path; the
     * order additionally wins any {@code getProvider(Class)} default resolution against the
     * built-in JPA user factory.
     */
    @Override
    public int order() {
        return DeclarativeUserProfileProviderFactory.PROVIDER_PRIORITY + 1;
    }
}
