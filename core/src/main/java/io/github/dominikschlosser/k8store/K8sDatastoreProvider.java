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

package io.github.dominikschlosser.k8store;

import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import io.github.dominikschlosser.k8store.spi.AbstractCrProviderFactory;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientScopeProvider;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.IdentityProviderStorageProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RevokedTokenProvider;
import org.keycloak.models.RoleProvider;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.provider.Provider;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.storage.MigrationManager;
import org.keycloak.storage.datastore.DefaultDatastoreProvider;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

/**
 * Datastore that serves the configured {@link Area areas} from Kubernetes custom resources and
 * delegates everything else (users, export/import, ...) to Keycloak's default storage by
 * inheriting from {@link DefaultDatastoreProvider}. The dynamic areas (user sessions, auth
 * sessions, login failures, single-use objects, revoked tokens) delegate the same way and only
 * resolve the CR-backed providers when their area is explicitly enabled - by default they stay
 * on Keycloak's database.
 */
public class K8sDatastoreProvider extends DefaultDatastoreProvider {

    private final KeycloakSession session;

    /**
     * The inherited fall-through paths (clientStorageManager(), getMigrationManager(), ...)
     * dereference the factory, so a real {@link DefaultDatastoreProviderFactory} is required -
     * Quarkus prunes the unselected {@code legacy} factory from the registry when the datastore
     * provider is fixed, so {@link K8sDatastoreProviderFactory} supplies its own initialized
     * delegate instance.
     */
    public K8sDatastoreProvider(KeycloakSession session, DefaultDatastoreProviderFactory legacyDelegate) {
        super(legacyDelegate, session);
        this.session = session;
    }

    private <T extends Provider> T k8s(Class<T> providerType) {
        return session.getProvider(providerType, AbstractCrProviderFactory.PROVIDER_ID);
    }

    @Override
    public ClientProvider clients() {
        return K8sStoreConfig.isAreaEnabled(Area.CLIENT) ? k8s(ClientProvider.class) : super.clients();
    }

    @Override
    public ClientProvider clientStorageManager() {
        return K8sStoreConfig.isAreaEnabled(Area.CLIENT) ? clients() : super.clientStorageManager();
    }

    @Override
    public ClientScopeProvider clientScopes() {
        return K8sStoreConfig.isAreaEnabled(Area.CLIENT_SCOPE) ? k8s(ClientScopeProvider.class) : super.clientScopes();
    }

    @Override
    public ClientScopeProvider clientScopeStorageManager() {
        return K8sStoreConfig.isAreaEnabled(Area.CLIENT_SCOPE) ? clientScopes() : super.clientScopeStorageManager();
    }

    @Override
    public GroupProvider groups() {
        return K8sStoreConfig.isAreaEnabled(Area.GROUP) ? k8s(GroupProvider.class) : super.groups();
    }

    @Override
    public GroupProvider groupStorageManager() {
        return K8sStoreConfig.isAreaEnabled(Area.GROUP) ? groups() : super.groupStorageManager();
    }

    @Override
    public RealmProvider realms() {
        return K8sStoreConfig.isAreaEnabled(Area.REALM) ? k8s(RealmProvider.class) : super.realms();
    }

    @Override
    public RoleProvider roles() {
        return K8sStoreConfig.isAreaEnabled(Area.ROLE) ? k8s(RoleProvider.class) : super.roles();
    }

    @Override
    public RoleProvider roleStorageManager() {
        return K8sStoreConfig.isAreaEnabled(Area.ROLE) ? roles() : super.roleStorageManager();
    }

    @Override
    public IdentityProviderStorageProvider identityProviders() {
        return K8sStoreConfig.isAreaEnabled(Area.IDENTITY_PROVIDER)
                ? k8s(IdentityProviderStorageProvider.class)
                : super.identityProviders();
    }

    /**
     * With the user area enabled, {@code users()} returns Keycloak's federation-aware
     * {@code UserStorageManager} (via the inherited {@link #userStorageManager()}) with the
     * CR-backed provider as its local storage: the manager resolves
     * {@code ((DefaultDatastoreProvider) datastore).userLocalStorage()}, which the override
     * below pins to the CR provider. User-storage federation (LDAP/Kerberos components)
     * therefore works exactly as with the JPA store - imported federated users become local
     * shadow users in the CR store ({@code addUser} + {@code setFederationLink}), and the
     * concrete {@code UserCredentialManager} keeps resolving credentials through
     * {@code userLocalStorage()}, which implements {@code UserCredentialStore}.
     *
     * <p>The one deliberate deviation from the inherited {@code users()} is skipping the
     * {@code UserCache} lookup - this extension runs stateless (the user cache is disabled),
     * and the infinispan cache provider would delegate to the JPA store anyway.
     */
    @Override
    public UserProvider users() {
        return K8sStoreConfig.isAreaEnabled(Area.USER) ? userStorageManager() : super.users();
    }

    @Override
    public UserProvider userLocalStorage() {
        return K8sStoreConfig.isAreaEnabled(Area.USER) ? k8s(UserProvider.class) : super.userLocalStorage();
    }

    @Override
    public UserSessionProvider userSessions() {
        return K8sStoreConfig.isAreaEnabled(Area.USER_SESSION) ? k8s(UserSessionProvider.class) : super.userSessions();
    }

    @Override
    public AuthenticationSessionProvider authSessions() {
        return K8sStoreConfig.isAreaEnabled(Area.AUTH_SESSION)
                ? k8s(AuthenticationSessionProvider.class)
                : super.authSessions();
    }

    @Override
    public UserLoginFailureProvider loginFailures() {
        return K8sStoreConfig.isAreaEnabled(Area.LOGIN_FAILURE)
                ? k8s(UserLoginFailureProvider.class)
                : super.loginFailures();
    }

    @Override
    public SingleUseObjectProvider singleUseObjects() {
        return K8sStoreConfig.isAreaEnabled(Area.SINGLE_USE_OBJECT)
                ? k8s(SingleUseObjectProvider.class)
                : super.singleUseObjects();
    }

    @Override
    public RevokedTokenProvider revokedTokens() {
        return K8sStoreConfig.isAreaEnabled(Area.REVOKED_TOKEN)
                ? k8s(RevokedTokenProvider.class)
                : super.revokedTokens();
    }

    @Override
    public MigrationManager getMigrationManager() {
        // CR-backed config entities are managed out-of-band; schema migrations do not apply
        return K8sStoreConfig.isAreaEnabled(Area.REALM) ? new CrMigrationManager(session) : super.getMigrationManager();
    }
}
