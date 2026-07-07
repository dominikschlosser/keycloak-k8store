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

import com.github.dominikschlosser.k8store.common.ListRewrites;
import static org.keycloak.utils.StreamsUtil.paginatedStream;

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.crd.IssuedVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.crd.UserConsentSpec;
import com.github.dominikschlosser.k8store.crd.UserSpec;
import com.github.dominikschlosser.k8store.crd.UserVerifiableCredentialSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.common.Profile;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.UserCredentialManager;
import org.keycloak.credential.UserCredentialStore;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IssuedVerifiableCredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ModelException;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserConsentModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserVerifiableCredentialModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.storage.StorageId;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;

/**
 * {@link UserProvider} serving users from {@code KeycloakUser} custom resources (experimental
 * {@code user} area).
 *
 * <p>Identity convention: user id = lowercased username at creation, immutable afterwards
 * (see {@link UserSpec}). Adapters are memoized per user id so repeated lookups within one
 * session mutate the same instance (read-your-write - login flows touch the same user many
 * times).
 *
 * <p>Credential wiring: this provider also implements {@link UserCredentialStore} against
 * {@code spec.credentials}, and {@link #getUserCredentialManager} returns Keycloak's standard
 * {@link UserCredentialManager} - hashing, validation fan-out over the registered credential
 * providers and policy enforcement are entirely upstream code; this store only persists the
 * resulting hashed {@link CredentialModel}s. The list order of {@code spec.credentials} is the
 * credential priority order.
 *
 * <p>Federation wiring: the datastore returns Keycloak's {@code UserStorageManager} with this
 * provider as its local storage ({@code userLocalStorage()}), so user-storage federation
 * (LDAP/Kerberos components) works as with the JPA store. Imported federated users are local
 * shadow users in this store, marked by {@code spec.federationLink};
 * {@link #removeImportedUsers} and {@link #unlinkUsers} operate on that marker. Lifecycle
 * events around removal ({@code UserPreRemovedEvent}) are the storage manager's job - this
 * provider only deletes, like the JPA provider.
 */
public class UserCrProvider implements UserProvider, UserCredentialStore {

    private static final Logger LOG = Logger.getLogger(UserCrProvider.class);

    private final KeycloakSession session;

    /** Adapter per (realm, user id), so repeated lookups return the same mutable instance. */
    private final Map<String, UserAdapter> knownAdapters = new HashMap<>();

    public UserCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private UserAdapter adapt(RealmModel realm, UserSpec spec) {
        return knownAdapters.computeIfAbsent(K8sStorageBackend.key(realm.getId(), spec.getId()),
                key -> new UserAdapter(session, realm, spec));
    }

    private Stream<UserSpec> specs(RealmModel realm) {
        return UserCrStore.allInRealm(realm.getId()).stream();
    }

    /**
     * The spec behind any {@link UserModel} handed to this provider: the memoized adapter's
     * instance when there is one (read-your-write), a fresh mirror copy otherwise.
     */
    private UserAdapter adapterOf(RealmModel realm, UserModel user) {
        if (user instanceof UserAdapter adapter) {
            return adapter;
        }
        UserSpec spec = UserCrStore.read(realm.getId(), user.getId());
        return spec == null ? null : adapt(realm, spec);
    }

    private UserAdapter adapterById(RealmModel realm, String userId) {
        UserAdapter known = knownAdapters.get(K8sStorageBackend.key(realm.getId(), userId));
        if (known != null) {
            return known;
        }
        UserSpec spec = UserCrStore.read(realm.getId(), userId);
        return spec == null ? null : adapt(realm, spec);
    }

    // ------------------------------------------------------------------ registration

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        return addUser(realm, null, username, true, true);
    }

    @Override
    public UserModel addUser(RealmModel realm, String id, String username,
                             boolean addDefaultRoles, boolean addDefaultRequiredActions) {
        if (username == null || username.isBlank()) {
            throw new ModelException("Username cannot be null or blank");
        }
        String normalized = username.toLowerCase();
        if (getUserByUsername(realm, normalized) != null) {
            throw new ModelDuplicateException("User with username " + normalized + " exists in realm "
                    + realm.getName());
        }
        String userId = id != null ? id : normalized;
        if (UserCrStore.exists(realm.getId(), userId)) {
            if (id != null) {
                throw new ModelDuplicateException("User with id " + id + " exists in realm " + realm.getName());
            }
            // the natural id is taken by a since-renamed user (ids never move); fall back to a
            // generated id - the rare exception to the human-readable-id convention
            userId = KeycloakModelUtils.generateId();
        } else if (id == null && !StorageId.isLocalStorage(userId)) {
            // a username like "f:x:y" would parse as a federated storage id and be routed to a
            // storage provider by UserStorageManager - never derive such an id from a username
            userId = KeycloakModelUtils.generateId();
        }

        UserSpec spec = new UserSpec();
        spec.setId(userId);
        spec.setRealm(realm.getId());
        spec.setUsername(normalized);
        spec.setEnabled(true);
        spec.setCreatedTimestamp(Time.currentTimeMillis());
        UserCrStore.save(spec);

        UserAdapter user = adapt(realm, spec);
        if (addDefaultRoles) {
            if (realm.getDefaultRole() != null) {
                user.grantRole(realm.getDefaultRole());
            }
            realm.getDefaultGroupsStream().forEach(user::joinGroup);
        }
        if (addDefaultRequiredActions) {
            realm.getRequiredActionProvidersStream()
                    .filter(RequiredActionProviderModel::isEnabled)
                    .filter(RequiredActionProviderModel::isDefaultAction)
                    .map(RequiredActionProviderModel::getAlias)
                    .forEach(user::addRequiredAction);
        }
        return user;
    }

    /**
     * Pure storage removal (JPA-provider parity): {@code UserPreRemovedEvent} is published by
     * the {@code UserStorageManager} in front of this provider - publishing it here too would
     * fire the dependents' cleanup (e.g. the authorization store's user-owned resources) twice.
     */
    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        if (UserCrStore.read(realm.getId(), user.getId()) == null) {
            return false;
        }
        knownAdapters.remove(K8sStorageBackend.key(realm.getId(), user.getId()));
        if (oid4vcEnabled()) {
            // upstream parity: a removed user takes its verifiable credentials and issuances along
            String userId = user.getId();
            VerifiableCredentialCrStore.credentialsInRealm(realm.getId()).stream()
                    .filter(vc -> userId.equals(vc.getUserId()))
                    .forEach(vc -> VerifiableCredentialCrStore.deleteCredential(realm.getId(), vc.getId()));
            VerifiableCredentialCrStore.issuedInRealm(realm.getId()).stream()
                    .filter(vc -> userId.equals(vc.getUserId()))
                    .forEach(vc -> VerifiableCredentialCrStore.deleteIssued(realm.getId(), vc.getId()));
        }
        UserCrStore.delete(realm.getId(), user.getId());
        return true;
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return adapterById(realm, id);
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String normalized = username.toLowerCase();
        return specs(realm)
                .filter(spec -> normalized.equals(spec.getUsername()))
                .findFirst()
                .map(spec -> (UserModel) adapt(realm, spec))
                .orElse(null);
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.toLowerCase();
        List<UserSpec> matches = specs(realm)
                .filter(spec -> normalized.equals(spec.getEmail()))
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            if (realm.isDuplicateEmailsAllowed()) {
                return null;
            }
            throw new ModelDuplicateException("Multiple users with email " + normalized + " exist in realm "
                    + realm.getName());
        }
        return adapt(realm, matches.get(0));
    }

    @Override
    public UserModel getServiceAccount(ClientModel client) {
        RealmModel realm = client.getRealm();
        return specs(realm)
                .filter(spec -> client.getId().equals(spec.getServiceAccountClientId()))
                .findFirst()
                .map(spec -> (UserModel) adapt(realm, spec))
                .orElse(null);
    }

    // ------------------------------------------------------------------ search

    @Override
    public Stream<UserModel> searchForUserStream(
            RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        Predicate<UserSpec> predicate = UserSearch.predicate(params == null ? Map.of() : params);
        Set<String> restrictedGroupIds = sessionGroupRestriction();
        Stream<UserModel> users = specs(realm)
                .filter(predicate)
                .filter(spec -> restrictedGroupIds == null || (spec.getGroups() != null
                        && spec.getGroups().stream().anyMatch(restrictedGroupIds::contains)))
                .map(spec -> (UserModel) adapt(realm, spec))
                .sorted(UserModel.COMPARE_BY_USERNAME);
        return paginatedStream(users, firstResult, maxResults);
    }

    /** Group restriction set by fine-grained admin permissions, or null when unrestricted. */
    @SuppressWarnings("unchecked")
    private Set<String> sessionGroupRestriction() {
        Object groups = session.getAttribute(UserModel.GROUPS);
        return groups instanceof Set ? (Set<String>) groups : null;
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String name, String value) {
        return specs(realm)
                .filter(spec -> spec.getAttributes() != null
                        && spec.getAttributes().getOrDefault(name, List.of()).contains(value))
                .map(spec -> (UserModel) adapt(realm, spec))
                .sorted(UserModel.COMPARE_BY_USERNAME);
    }

    // ------------------------------------------------------------------ members

    @Override
    public Stream<UserModel> getGroupMembersStream(
            RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        return paginatedStream(memberSpecs(realm, group)
                .map(spec -> (UserModel) adapt(realm, spec))
                .sorted(UserModel.COMPARE_BY_USERNAME), firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(
            RealmModel realm, GroupModel group, String search, Boolean exact, Integer firstResult,
            Integer maxResults) {
        Stream<UserSpec> members = memberSpecs(realm, group);
        if (search != null && !search.isBlank()) {
            Predicate<String> match = Boolean.TRUE.equals(exact)
                    ? field -> search.equalsIgnoreCase(field)
                    : field -> LikePatterns.insensitiveLike(field, "%" + search + "%");
            members = members.filter(spec -> UserSearch.anySearchField(spec, match));
        }
        return paginatedStream(members
                .map(spec -> (UserModel) adapt(realm, spec))
                .sorted(UserModel.COMPARE_BY_USERNAME), firstResult, maxResults);
    }

    private Stream<UserSpec> memberSpecs(RealmModel realm, GroupModel group) {
        return specs(realm)
                .filter(spec -> spec.getGroups() != null && spec.getGroups().contains(group.getId()));
    }

    @Override
    public Stream<UserModel> getRoleMembersStream(
            RealmModel realm, RoleModel role, Integer firstResult, Integer maxResults) {
        Predicate<UserSpec> hasGrant;
        if (role.isClientRole()) {
            hasGrant = spec -> spec.getClientRoles() != null
                    && spec.getClientRoles().getOrDefault(role.getContainerId(), List.of())
                            .contains(role.getName());
        } else {
            hasGrant = spec -> spec.getRealmRoles() != null && spec.getRealmRoles().contains(role.getName());
        }
        return paginatedStream(specs(realm)
                .filter(hasGrant)
                .map(spec -> (UserModel) adapt(realm, spec))
                .sorted(UserModel.COMPARE_BY_USERNAME), firstResult, maxResults);
    }

    // ------------------------------------------------------------------ counts

    @Override
    public int getUsersCount(RealmModel realm) {
        return getUsersCount(realm, false);
    }

    @Override
    public int getUsersCount(RealmModel realm, boolean includeServiceAccount) {
        Stream<UserSpec> users = specs(realm);
        if (!includeServiceAccount) {
            users = users.filter(spec -> spec.getServiceAccountClientId() == null);
        }
        return (int) users.count();
    }

    @Override
    public int getUsersCount(RealmModel realm, Set<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return 0;
        }
        return (int) specs(realm)
                .filter(spec -> spec.getServiceAccountClientId() == null)
                .filter(spec -> spec.getGroups() != null
                        && spec.getGroups().stream().anyMatch(groupIds::contains))
                .count();
    }

    @Override
    public int getUsersCount(RealmModel realm, String search) {
        return getUsersCount(realm, Map.of(UserModel.SEARCH, search));
    }

    @Override
    public int getUsersCount(RealmModel realm, String search, Set<String> groupIds) {
        return getUsersCount(realm, Map.of(UserModel.SEARCH, search), groupIds);
    }

    @Override
    public int getUsersCount(RealmModel realm, Map<String, String> params) {
        return (int) specs(realm).filter(UserSearch.predicate(params == null ? Map.of() : params)).count();
    }

    @Override
    public int getUsersCount(RealmModel realm, Map<String, String> params, Set<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return 0;
        }
        return (int) specs(realm)
                .filter(UserSearch.predicate(params == null ? Map.of() : params))
                .filter(spec -> spec.getGroups() != null
                        && spec.getGroups().stream().anyMatch(groupIds::contains))
                .count();
    }

    // ------------------------------------------------------------------ bulk updates

    @Override
    public void grantToAllUsers(RealmModel realm, RoleModel role) {
        specs(realm).forEach(spec -> adapt(realm, spec).grantRole(role));
    }

    @Override
    public void setNotBeforeForUser(RealmModel realm, UserModel user, int notBefore) {
        UserAdapter adapter = adapterOf(realm, user);
        if (adapter == null) {
            return;
        }
        if (!Objects.equals(adapter.spec().getNotBefore(), notBefore)) {
            adapter.spec().setNotBefore(notBefore);
            adapter.persist();
        }
    }

    @Override
    public int getNotBeforeOfUser(RealmModel realm, UserModel user) {
        UserAdapter adapter = adapterOf(realm, user);
        Integer notBefore = adapter == null ? null : adapter.spec().getNotBefore();
        return notBefore == null ? 0 : notBefore;
    }

    // ------------------------------------------------------------------ imported-user maintenance

    @Override
    public void removeImportedUsers(RealmModel realm, String storageProviderId) {
        specs(realm)
                .filter(spec -> storageProviderId.equals(spec.getFederationLink()))
                .forEach(spec -> UserCrStore.delete(realm.getId(), spec.getId()));
    }

    @Override
    public void unlinkUsers(RealmModel realm, String storageProviderId) {
        specs(realm)
                .filter(spec -> storageProviderId.equals(spec.getFederationLink()))
                .forEach(spec -> {
                    spec.setFederationLink(null);
                    UserCrStore.save(spec);
                });
    }

    // ------------------------------------------------------------------ consents

    @Override
    public void addConsent(RealmModel realm, String userId, UserConsentModel consent) {
        UserAdapter adapter = requireAdapter(realm, userId);
        String clientId = consent.getClient().getId();
        if (findConsent(adapter.spec(), clientId) != null) {
            throw new ModelDuplicateException("Consent already exists for client " + clientId
                    + " and user " + userId);
        }
        long now = Time.currentTimeMillis();
        consent.setCreatedDate(now);
        consent.setLastUpdatedDate(now);
        List<UserConsentSpec> consents = adapter.spec().getConsents();
        if (consents == null) {
            consents = new ArrayList<>();
            adapter.spec().setConsents(consents);
        }
        consents.add(consentToSpec(consent));
        adapter.persist();
    }

    @Override
    public UserConsentModel getConsentByClient(RealmModel realm, String userId, String clientInternalId) {
        UserAdapter adapter = adapterById(realm, userId);
        if (adapter == null) {
            return null;
        }
        UserConsentSpec rep = findConsent(adapter.spec(), clientInternalId);
        return rep == null ? null : consentToModel(realm, rep);
    }

    @Override
    public Stream<UserConsentModel> getConsentsStream(RealmModel realm, String userId) {
        UserAdapter adapter = adapterById(realm, userId);
        List<UserConsentSpec> consents = adapter == null ? null : adapter.spec().getConsents();
        if (consents == null) {
            return Stream.empty();
        }
        return new ArrayList<>(consents).stream()
                .map(rep -> consentToModel(realm, rep))
                .filter(Objects::nonNull);
    }

    @Override
    public void updateConsent(RealmModel realm, String userId, UserConsentModel consent) {
        UserAdapter adapter = requireAdapter(realm, userId);
        UserConsentSpec rep = findConsent(adapter.spec(), consent.getClient().getId());
        if (rep == null) {
            throw new ModelException("Consent not found for client " + consent.getClient().getId()
                    + " and user " + userId);
        }
        consent.setLastUpdatedDate(Time.currentTimeMillis());
        rep.setGrantedClientScopes(consent.getGrantedClientScopes().stream()
                .map(ClientScopeModel::getName)
                .collect(Collectors.toList()));
        rep.setGrantedScopeParameters(scopeParametersOf(consent));
        rep.setLastUpdatedDate(consent.getLastUpdatedDate());
        adapter.persist();
    }

    @Override
    public boolean revokeConsentForClient(RealmModel realm, String userId, String clientInternalId) {
        UserAdapter adapter = adapterById(realm, userId);
        if (adapter == null || adapter.spec().getConsents() == null) {
            return false;
        }
        boolean removed = adapter.spec().getConsents()
                .removeIf(rep -> clientInternalId.equals(rep.getClientId()));
        if (removed) {
            adapter.persist();
        }
        return removed;
    }

    private UserAdapter requireAdapter(RealmModel realm, String userId) {
        UserAdapter adapter = adapterById(realm, userId);
        if (adapter == null) {
            throw new ModelException("User not found: " + userId);
        }
        return adapter;
    }

    private static UserConsentSpec findConsent(UserSpec spec, String clientId) {
        if (spec.getConsents() == null) {
            return null;
        }
        return spec.getConsents().stream()
                .filter(rep -> clientId.equals(rep.getClientId()))
                .findFirst()
                .orElse(null);
    }

    private static UserConsentSpec consentToSpec(UserConsentModel consent) {
        UserConsentSpec rep = new UserConsentSpec();
        rep.setClientId(consent.getClient().getId());
        rep.setGrantedClientScopes(consent.getGrantedClientScopes().stream()
                .map(ClientScopeModel::getName)
                .collect(Collectors.toList()));
        rep.setGrantedScopeParameters(scopeParametersOf(consent));
        rep.setCreatedDate(consent.getCreatedDate());
        rep.setLastUpdatedDate(consent.getLastUpdatedDate());
        return rep;
    }

    /**
     * The per-scope parameters of the (experimental) parameterized-scopes feature, keyed by
     * scope name - {@code null} when no granted scope carries any (the common case), so plain
     * consents keep their D2 CR shape.
     */
    private static Map<String, List<String>> scopeParametersOf(UserConsentModel consent) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (ClientScopeModel scope : consent.getGrantedClientScopes()) {
            List<String> values = consent.getParameters(scope);
            if (values != null && !values.isEmpty()) {
                parameters.put(scope.getName(), new ArrayList<>(values));
            }
        }
        return parameters.isEmpty() ? null : parameters;
    }

    /**
     * Scope names resolve through the client-scope provider; stale names are skipped. A
     * parameterized scope is re-granted with its stored parameters; if it has none stored (it
     * became parameterized after the grant), it counts as not granted - the JPA store has no
     * rows to load in that case either.
     */
    private UserConsentModel consentToModel(RealmModel realm, UserConsentSpec rep) {
        ClientModel client = session.clients().getClientById(realm, rep.getClientId());
        if (client == null) {
            return null;
        }
        UserConsentModel consent = new UserConsentModel(client);
        consent.setCreatedDate(rep.getCreatedDate());
        consent.setLastUpdatedDate(rep.getLastUpdatedDate());
        if (rep.getGrantedClientScopes() != null && !rep.getGrantedClientScopes().isEmpty()) {
            Map<String, ClientScopeModel> byName = session.clientScopes().getClientScopesStream(realm)
                    .collect(Collectors.toMap(ClientScopeModel::getName, Function.identity(), (a, b) -> a));
            for (String scopeName : rep.getGrantedClientScopes()) {
                ClientScopeModel scope = byName.get(scopeName);
                if (scope == null) {
                    continue;
                }
                if (ClientScopeModel.isParameterizedScope(scope)) {
                    List<String> values = rep.getGrantedScopeParameters() == null
                            ? null
                            : rep.getGrantedScopeParameters().get(scopeName);
                    if (values != null) {
                        values.forEach(value -> consent.addGrantedClientScope(scope, value));
                    }
                } else {
                    consent.addGrantedClientScope(scope);
                }
            }
        }
        return consent;
    }

    // ------------------------------------------------------------------ federated identities

    @Override
    public void addFederatedIdentity(RealmModel realm, UserModel user, FederatedIdentityModel link) {
        UserAdapter adapter = adapterOf(realm, user);
        if (adapter == null) {
            return;
        }
        List<FederatedIdentityRepresentation> identities = adapter.spec().getFederatedIdentities();
        if (identities == null) {
            identities = new ArrayList<>();
            adapter.spec().setFederatedIdentities(identities);
        }
        identities.add(identityToRepresentation(link));
        storeIdentityToken(adapter.spec(), link.getIdentityProvider(), link.getToken());
        adapter.persist();
    }

    @Override
    public boolean removeFederatedIdentity(RealmModel realm, UserModel user, String socialProvider) {
        UserAdapter adapter = adapterOf(realm, user);
        if (adapter == null || adapter.spec().getFederatedIdentities() == null) {
            return false;
        }
        boolean removed = adapter.spec().getFederatedIdentities()
                .removeIf(rep -> socialProvider.equals(rep.getIdentityProvider()));
        if (removed) {
            if (adapter.spec().getFederatedIdentityTokens() != null) {
                adapter.spec().getFederatedIdentityTokens().remove(socialProvider);
            }
            adapter.persist();
        }
        return removed;
    }

    @Override
    public void updateFederatedIdentity(RealmModel realm, UserModel user, FederatedIdentityModel link) {
        UserAdapter adapter = adapterOf(realm, user);
        if (adapter == null || adapter.spec().getFederatedIdentities() == null) {
            return;
        }
        List<FederatedIdentityRepresentation> identities = adapter.spec().getFederatedIdentities();
        for (int i = 0; i < identities.size(); i++) {
            if (link.getIdentityProvider().equals(identities.get(i).getIdentityProvider())) {
                identities.set(i, identityToRepresentation(link));
                storeIdentityToken(adapter.spec(), link.getIdentityProvider(), link.getToken());
                adapter.persist();
                return;
            }
        }
    }

    @Override
    public Stream<FederatedIdentityModel> getFederatedIdentitiesStream(RealmModel realm, UserModel user) {
        UserAdapter adapter = adapterOf(realm, user);
        List<FederatedIdentityRepresentation> identities =
                adapter == null ? null : adapter.spec().getFederatedIdentities();
        if (identities == null) {
            return Stream.empty();
        }
        UserSpec spec = adapter.spec();
        return new ArrayList<>(identities).stream().map(rep -> identityToModel(spec, rep));
    }

    @Override
    public FederatedIdentityModel getFederatedIdentity(RealmModel realm, UserModel user, String socialProvider) {
        return getFederatedIdentitiesStream(realm, user)
                .filter(link -> socialProvider.equals(link.getIdentityProvider()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public UserModel getUserByFederatedIdentity(RealmModel realm, FederatedIdentityModel socialLink) {
        List<UserSpec> matches = specs(realm)
                .filter(spec -> spec.getFederatedIdentities() != null && spec.getFederatedIdentities().stream()
                        .anyMatch(rep -> socialLink.getIdentityProvider().equals(rep.getIdentityProvider())
                                && Objects.equals(socialLink.getUserId(), rep.getUserId())))
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new ModelDuplicateException("More than one user with the federated identity "
                    + socialLink.getIdentityProvider() + "/" + socialLink.getUserId() + " exists in realm "
                    + realm.getName());
        }
        return adapt(realm, matches.get(0));
    }

    private static FederatedIdentityRepresentation identityToRepresentation(FederatedIdentityModel link) {
        FederatedIdentityRepresentation rep = new FederatedIdentityRepresentation();
        rep.setIdentityProvider(link.getIdentityProvider());
        rep.setUserId(link.getUserId());
        rep.setUserName(link.getUserName());
        return rep;
    }

    private static FederatedIdentityModel identityToModel(UserSpec spec, FederatedIdentityRepresentation rep) {
        String token = spec.getFederatedIdentityTokens() == null
                ? null
                : spec.getFederatedIdentityTokens().get(rep.getIdentityProvider());
        return new FederatedIdentityModel(rep.getIdentityProvider(), rep.getUserId(), rep.getUserName(), token);
    }

    private static void storeIdentityToken(UserSpec spec, String providerAlias, String token) {
        if (token == null) {
            return;
        }
        Map<String, String> tokens = spec.getFederatedIdentityTokens();
        if (tokens == null) {
            tokens = new LinkedHashMap<>();
            spec.setFederatedIdentityTokens(tokens);
        }
        tokens.put(providerAlias, token);
    }

    // ------------------------------------------------------------------ credential storage (spec.credentials)

    /**
     * Keycloak's standard credential manager over this provider's {@link UserCredentialStore} (it
     * resolves the store through the datastore's {@code userLocalStorage()}). The concrete
     * {@code credential.UserCredentialManager} is a subtype of the SPI's
     * {@code models.UserCredentialManager} return type, so a covariant return keeps the simple
     * name imported instead of spelling out either fully qualified.
     */
    @Override
    public UserCredentialManager getUserCredentialManager(UserModel user) {
        return new UserCredentialManager(session, session.getContext().getRealm(), user);
    }

    @Override
    public CredentialModel createCredential(RealmModel realm, UserModel user, CredentialModel credential) {
        UserAdapter adapter = adapterOf(realm, user);
        if (adapter == null) {
            throw new ModelException("User not found: " + user.getId());
        }
        if (credential.getId() == null) {
            credential.setId(KeycloakModelUtils.generateId());
        }
        if (credential.getCreatedDate() == null) {
            credential.setCreatedDate(Time.currentTimeMillis());
        }
        List<CredentialRepresentation> credentials = adapter.spec().getCredentials();
        if (credentials == null) {
            credentials = new ArrayList<>();
            adapter.spec().setCredentials(credentials);
        }
        credentials.add(credentialToRepresentation(credential));
        adapter.persist();
        return credential;
    }

    @Override
    public void updateCredential(RealmModel realm, UserModel user, CredentialModel credential) {
        UserAdapter adapter = adapterOf(realm, user);
        List<CredentialRepresentation> credentials = adapter == null ? null : adapter.spec().getCredentials();
        if (credentials == null) {
            return;
        }
        for (int i = 0; i < credentials.size(); i++) {
            if (credential.getId() != null && credential.getId().equals(credentials.get(i).getId())) {
                credentials.set(i, credentialToRepresentation(credential));
                adapter.persist();
                return;
            }
        }
    }

    @Override
    public boolean removeStoredCredential(RealmModel realm, UserModel user, String id) {
        UserAdapter adapter = adapterOf(realm, user);
        List<CredentialRepresentation> credentials = adapter == null ? null : adapter.spec().getCredentials();
        if (credentials == null) {
            return false;
        }
        boolean removed = credentials.removeIf(rep -> id.equals(rep.getId()));
        if (removed) {
            adapter.persist();
        }
        return removed;
    }

    @Override
    public CredentialModel getStoredCredentialById(RealmModel realm, UserModel user, String id) {
        return getStoredCredentialsStream(realm, user)
                .filter(credential -> id.equals(credential.getId()))
                .findFirst()
                .orElse(null);
    }

    /** List order is the priority order - no separate priority field. */
    @Override
    public Stream<CredentialModel> getStoredCredentialsStream(RealmModel realm, UserModel user) {
        UserAdapter adapter = adapterOf(realm, user);
        List<CredentialRepresentation> credentials = adapter == null ? null : adapter.spec().getCredentials();
        if (credentials == null) {
            return Stream.empty();
        }
        return new ArrayList<>(credentials).stream().map(UserCrProvider::credentialToModel);
    }

    @Override
    public Stream<CredentialModel> getStoredCredentialsByTypeStream(RealmModel realm, UserModel user, String type) {
        return getStoredCredentialsStream(realm, user)
                .filter(credential -> Objects.equals(type, credential.getType()));
    }

    @Override
    public CredentialModel getStoredCredentialByNameAndType(
            RealmModel realm, UserModel user, String name, String type) {
        return getStoredCredentialsStream(realm, user)
                .filter(credential -> Objects.equals(type, credential.getType())
                        && Objects.equals(name, credential.getUserLabel()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean moveCredentialTo(RealmModel realm, UserModel user, String id, String newPreviousCredentialId) {
        UserAdapter adapter = adapterOf(realm, user);
        List<CredentialRepresentation> credentials = adapter == null ? null : adapter.spec().getCredentials();
        if (credentials == null) {
            return false;
        }
        CredentialRepresentation moved = credentials.stream()
                .filter(rep -> id.equals(rep.getId()))
                .findFirst()
                .orElse(null);
        if (moved == null) {
            return false;
        }
        credentials.remove(moved);
        if (newPreviousCredentialId == null) {
            credentials.add(0, moved);
        } else {
            int anchor = -1;
            for (int i = 0; i < credentials.size(); i++) {
                if (newPreviousCredentialId.equals(credentials.get(i).getId())) {
                    anchor = i;
                    break;
                }
            }
            if (anchor < 0) {
                credentials.add(moved);
                adapter.persist();
                return false;
            }
            credentials.add(anchor + 1, moved);
        }
        adapter.persist();
        return true;
    }

    /** Field-by-field copy - Keycloak's representation utilities redact secret data. */
    private static CredentialRepresentation credentialToRepresentation(CredentialModel credential) {
        CredentialRepresentation rep = new CredentialRepresentation();
        rep.setId(credential.getId());
        rep.setType(credential.getType());
        rep.setUserLabel(credential.getUserLabel());
        rep.setCreatedDate(credential.getCreatedDate());
        rep.setSecretData(credential.getSecretData());
        rep.setCredentialData(credential.getCredentialData());
        return rep;
    }

    private static CredentialModel credentialToModel(CredentialRepresentation rep) {
        CredentialModel credential = new CredentialModel();
        credential.setId(rep.getId());
        credential.setType(rep.getType());
        credential.setUserLabel(rep.getUserLabel());
        credential.setCreatedDate(rep.getCreatedDate());
        credential.setSecretData(rep.getSecretData());
        credential.setCredentialData(rep.getCredentialData());
        return credential;
    }

    // ------------------------------------------------------------------ OID4VC verifiable credentials

    /*
     * Storage of the OID4VC surface (experimental oid4vc-vci feature): two CR kinds registered
     * only when the feature is enabled together with the user area. Semantics mirror the
     * upstream JPA store: ids are generated, revisions are secure random ids regenerated on
     * update, the user-attribute snapshot falls back to the user profile's readable attributes,
     * issued credentials inherit the referenced credential's revision, and removing a
     * verifiable credential removes its issuances. The SPI is realm-blind (user/credential ids
     * only); this store resolves the realm from the session context - correct for the issuance
     * and admin paths, which always run in a realm context - and falls back to a cross-realm
     * scan for by-id lookups (ids are generated, so unambiguous).
     *
     * Expiry deviation from upstream: issued credentials carry expiresAt and are wired into the
     * store's expiry handling (reads filter them, the background reaper deletes the CRs).
     * Upstream keeps expired rows listable until a scheduled cleanup task runs - a task that is
     * never registered under this datastore, so the reaper is its replacement and
     * removeExpiredIssuedVerifiableCredentials needs no work of its own.
     */

    private boolean oid4vcEnabled() {
        // a null profile (outside a booted server) means no features are enabled
        return Profile.getInstance() != null && Profile.isFeatureEnabled(Profile.Feature.OID4VC_VCI);
    }

    private static ModelException oid4vcDisabled() {
        return new ModelException(
                "OID4VC verifiable-credential storage requires the oid4vc-vci feature");
    }

    private String contextRealmId() {
        RealmModel realm = session.getContext().getRealm();
        return realm == null ? null : realm.getId();
    }

    /** The user's credential specs, scoped to the context realm when one is set. */
    private Stream<UserVerifiableCredentialSpec> credentialSpecsOfUser(String userId) {
        String realmId = contextRealmId();
        List<UserVerifiableCredentialSpec> specs = realmId != null
                ? VerifiableCredentialCrStore.credentialsInRealm(realmId)
                : VerifiableCredentialCrStore.allCredentials();
        return specs.stream().filter(spec -> userId.equals(spec.getUserId()));
    }

    private Stream<IssuedVerifiableCredentialSpec> issuedSpecsOfUser(String userId) {
        String realmId = contextRealmId();
        List<IssuedVerifiableCredentialSpec> specs = realmId != null
                ? VerifiableCredentialCrStore.issuedInRealm(realmId)
                : VerifiableCredentialCrStore.allIssued();
        return specs.stream().filter(spec -> userId.equals(spec.getUserId()));
    }

    /** Snapshot of the user's readable profile attributes (upstream stores the same snapshot). */
    private Map<String, List<String>> profileAttributeSnapshot(RealmModel realm, String userId) {
        UserAdapter user = adapterById(realm, userId);
        if (user == null) {
            throw new ModelException("User " + userId + " not found in realm " + realm.getName());
        }
        UserProfileProvider profiles = session.getProvider(UserProfileProvider.class);
        return profiles.create(UserProfileContext.USER_API, user).getAttributes().getReadable();
    }

    @Override
    public UserVerifiableCredentialModel addVerifiableCredential(
            String userId, UserVerifiableCredentialModel credential) {
        if (!oid4vcEnabled()) {
            throw oid4vcDisabled();
        }
        if (credential.getClientScopeId() == null) {
            throw new ModelException("Credential scope not specified");
        }
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            throw new ModelException("No realm in context for verifiable-credential storage");
        }
        UserVerifiableCredentialSpec spec = new UserVerifiableCredentialSpec();
        spec.setId(KeycloakModelUtils.generateId());
        spec.setRealm(realm.getId());
        spec.setUserId(userId);
        spec.setClientScopeId(credential.getClientScopeId());
        spec.setRevision(credential.getRevision() != null
                ? credential.getRevision()
                : SecretGenerator.getInstance().generateSecureID());
        long created = credential.getCreatedDate() != null ? credential.getCreatedDate() : Time.currentTimeMillis();
        spec.setCreatedDate(created);
        spec.setUpdatedDate(credential.getUpdatedDate() != null ? credential.getUpdatedDate() : created);
        spec.setUserAttributes(credential.getUserAttributes() != null
                ? credential.getUserAttributes()
                : profileAttributeSnapshot(realm, userId));
        VerifiableCredentialCrStore.saveCredential(spec);
        return credentialSpecToModel(spec);
    }

    /**
     * The second parameter is the <em>client scope id</em>, not the credential id - upstream
     * addresses a user's credential by its scope (one credential per user and scope), verified
     * against the JPA implementation and the admin resource, which passes {@code scope.getId()}.
     */
    @Override
    public UserVerifiableCredentialModel updateVerifiableCredential(String userId, String clientScopeId) {
        if (!oid4vcEnabled()) {
            throw oid4vcDisabled();
        }
        RealmModel realm = session.getContext().getRealm();
        if (realm == null || adapterById(realm, userId) == null) {
            throw new ModelException("User " + userId + " not found in the context realm");
        }
        UserVerifiableCredentialSpec spec = credentialSpecsOfUser(userId)
                .filter(candidate -> clientScopeId.equals(candidate.getClientScopeId()))
                .findFirst()
                .orElseThrow(() -> new ModelException(
                        "Verifiable credential of scope " + clientScopeId + " not found for user " + userId));
        // upstream update semantics: refresh the attribute snapshot, roll the revision
        spec.setUserAttributes(profileAttributeSnapshot(realm, userId));
        spec.setRevision(SecretGenerator.getInstance().generateSecureID());
        spec.setUpdatedDate(Time.currentTimeMillis());
        VerifiableCredentialCrStore.saveCredential(spec);
        return credentialSpecToModel(spec);
    }

    /** Like {@link #updateVerifiableCredential}, the second parameter is the client scope id. */
    @Override
    public boolean removeVerifiableCredential(String userId, String clientScopeId) {
        if (!oid4vcEnabled()) {
            return false;
        }
        UserVerifiableCredentialSpec spec = credentialSpecsOfUser(userId)
                .filter(candidate -> clientScopeId.equals(candidate.getClientScopeId()))
                .findFirst()
                .orElse(null);
        if (spec == null) {
            return false;
        }
        // upstream parity: removing a credential removes the user's issuances of it
        issuedSpecsOfUser(userId)
                .filter(issued -> spec.getId().equals(issued.getVerifiableCredentialId()))
                .forEach(issued -> VerifiableCredentialCrStore.deleteIssued(issued.getRealm(), issued.getId()));
        VerifiableCredentialCrStore.deleteCredential(spec.getRealm(), spec.getId());
        return true;
    }

    @Override
    public Stream<UserVerifiableCredentialModel> getVerifiableCredentialsByUser(String userId) {
        if (!oid4vcEnabled()) {
            return Stream.empty();
        }
        return credentialSpecsOfUser(userId)
                .map(UserCrProvider::credentialSpecToModel)
                .sorted(Comparator.comparing(UserVerifiableCredentialModel::getClientScopeId));
    }

    @Override
    public UserVerifiableCredentialModel getVerifiableCredentialById(String credentialId) {
        if (!oid4vcEnabled() || credentialId == null) {
            return null;
        }
        UserVerifiableCredentialSpec spec = VerifiableCredentialCrStore.findCredentialById(credentialId);
        return spec == null ? null : credentialSpecToModel(spec);
    }

    @Override
    public UserVerifiableCredentialModel getVerifiableCredentialByClientScope(String userId, String clientScopeId) {
        if (!oid4vcEnabled()) {
            return null;
        }
        return credentialSpecsOfUser(userId)
                .filter(spec -> clientScopeId.equals(spec.getClientScopeId()))
                .findFirst()
                .map(UserCrProvider::credentialSpecToModel)
                .orElse(null);
    }

    @Override
    public IssuedVerifiableCredentialModel addIssuedVerifiableCredential(IssuedVerifiableCredentialModel credential) {
        if (!oid4vcEnabled()) {
            throw oid4vcDisabled();
        }
        UserVerifiableCredentialSpec referenced =
                VerifiableCredentialCrStore.findCredentialById(credential.getVerifiableCredentialId());
        String revision = credential.getRevision();
        if (revision == null) {
            // upstream parity: an issuance without an explicit revision inherits the
            // referenced credential's current revision - and requires it to exist
            if (referenced == null) {
                throw new ModelException("Verifiable credential " + credential.getVerifiableCredentialId()
                        + " not found");
            }
            revision = referenced.getRevision();
        }
        String realmId = referenced != null ? referenced.getRealm() : contextRealmId();
        if (realmId == null) {
            throw new ModelException("No realm in context for issued-credential storage");
        }
        IssuedVerifiableCredentialSpec spec = new IssuedVerifiableCredentialSpec();
        spec.setId(SecretGenerator.getInstance().generateSecureID());
        spec.setRealm(realmId);
        spec.setUserId(credential.getUserId());
        spec.setVerifiableCredentialId(credential.getVerifiableCredentialId());
        spec.setClientId(credential.getClientId());
        spec.setRevision(revision);
        spec.setIssuedAt(credential.getIssuedAt() != null ? credential.getIssuedAt() : Time.currentTimeMillis());
        spec.setExpiresAt(credential.getExpiresAt());
        VerifiableCredentialCrStore.saveIssued(spec);
        return issuedSpecToModel(spec);
    }

    @Override
    public Stream<IssuedVerifiableCredentialModel> getIssuedVerifiableCredentialsStreamByUser(String userId) {
        if (!oid4vcEnabled()) {
            return Stream.empty();
        }
        return issuedSpecsOfUser(userId)
                .map(UserCrProvider::issuedSpecToModel)
                .sorted(Comparator.comparing(IssuedVerifiableCredentialModel::getIssuedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
    }

    @Override
    public boolean removeIssuedVerifiableCredential(String credentialId) {
        if (!oid4vcEnabled() || credentialId == null) {
            return false;
        }
        IssuedVerifiableCredentialSpec spec = VerifiableCredentialCrStore.findIssuedById(credentialId);
        if (spec == null) {
            return false;
        }
        VerifiableCredentialCrStore.deleteIssued(spec.getRealm(), spec.getId());
        return true;
    }

    @Override
    public void removeExpiredIssuedVerifiableCredentials() {
        // expiry is the store's job here: reads filter expired issued credentials and the
        // background reaper deletes their CRs (upstream's scheduled cleanup task is not
        // registered under this datastore)
    }

    private static UserVerifiableCredentialModel credentialSpecToModel(UserVerifiableCredentialSpec spec) {
        UserVerifiableCredentialModel model =
                new UserVerifiableCredentialModel(spec.getId(), spec.getClientScopeId());
        model.setRevision(spec.getRevision());
        model.setCreatedDate(spec.getCreatedDate());
        model.setUpdatedDate(spec.getUpdatedDate());
        model.setUserAttributes(spec.getUserAttributes());
        return model;
    }

    private static IssuedVerifiableCredentialModel issuedSpecToModel(IssuedVerifiableCredentialSpec spec) {
        IssuedVerifiableCredentialModel model = new IssuedVerifiableCredentialModel();
        model.setId(spec.getId());
        model.setUserId(spec.getUserId());
        model.setVerifiableCredentialId(spec.getVerifiableCredentialId());
        model.setClientId(spec.getClientId());
        model.setRevision(spec.getRevision());
        model.setIssuedAt(spec.getIssuedAt());
        model.setExpiresAt(spec.getExpiresAt());
        return model;
    }

    // ------------------------------------------------------------------ removal cascades

    @Override
    public void preRemove(RealmModel realm) {
        realmRemoved(realm);
    }

    @Override
    public void preRemove(RealmModel realm, IdentityProviderModel provider) {
        identityProviderRemoved(realm, provider.getAlias());
    }

    @Override
    public void preRemove(RealmModel realm, RoleModel role) {
        roleRemoved(realm, role);
    }

    @Override
    public void preRemove(RealmModel realm, GroupModel group) {
        groupRemoved(realm, group);
    }

    @Override
    public void preRemove(RealmModel realm, ClientModel client) {
        clientRemoved(realm, client);
    }

    @Override
    public void preRemove(ProtocolMapperModel protocolMapper) {
        // consents store granted scopes by name only; nothing references protocol mappers
    }

    @Override
    public void preRemove(ClientScopeModel clientScope) {
        clientScopeRemoved(clientScope.getRealm(), clientScope);
    }

    @Override
    public void preRemove(RealmModel realm, ComponentModel component) {
        // JPA parity: removing a user-storage component does not delete its imported users
        // here - the storage manager validates them lazily and removeImportedUsers/unlinkUsers
        // are the explicit cleanup paths. (JPA only cleans deprecated client-storage consents.)
    }

    /** Realm removal: drop every user CR of the realm, credentials and all, plus its VCs. */
    void realmRemoved(RealmModel realm) {
        specs(realm).forEach(spec -> UserCrStore.delete(realm.getId(), spec.getId()));
        if (oid4vcEnabled()) {
            VerifiableCredentialCrStore.credentialsInRealm(realm.getId())
                    .forEach(vc -> VerifiableCredentialCrStore.deleteCredential(realm.getId(), vc.getId()));
            VerifiableCredentialCrStore.issuedInRealm(realm.getId())
                    .forEach(vc -> VerifiableCredentialCrStore.deleteIssued(realm.getId(), vc.getId()));
        }
    }

    /** Role removal cascade: purge the removed role from every user's grants. */
    void roleRemoved(RealmModel realm, RoleModel removed) {
        specs(realm).forEach(spec -> {
            boolean changed;
            if (removed.isClientRole()) {
                Map<String, List<String>> byClient = spec.getClientRoles();
                List<String> names = byClient == null ? null : byClient.get(removed.getContainerId());
                changed = names != null && names.remove(removed.getName());
                if (changed && names.isEmpty()) {
                    byClient.remove(removed.getContainerId());
                }
            } else {
                changed = spec.getRealmRoles() != null && spec.getRealmRoles().remove(removed.getName());
            }
            if (changed) {
                LOG.tracef("Dropping removed role %s from grants of user %s", removed.getName(), spec.getId());
                UserCrStore.save(spec);
            }
        });
    }

    /**
     * Role rename cascade: swap the old role name for the new one in every user's grants,
     * preserving list position so the grant ordering stays stable.
     */
    void roleRenamed(RealmModel realm, RoleModel renamed, String newName) {
        String oldName = renamed.getName();
        specs(realm).forEach(spec -> {
            boolean changed;
            if (renamed.isClientRole()) {
                Map<String, List<String>> byClient = spec.getClientRoles();
                List<String> names = byClient == null ? null : byClient.get(renamed.getContainerId());
                changed = ListRewrites.replaceInList(names, oldName, newName);
            } else {
                changed = ListRewrites.replaceInList(spec.getRealmRoles(), oldName, newName);
            }
            if (changed) {
                LOG.tracef("Rewriting renamed role %s to %s in grants of user %s",
                        oldName, newName, spec.getId());
                UserCrStore.save(spec);
            }
        });
    }


    /** Group removal cascade: purge the removed group from every user's membership. */
    void groupRemoved(RealmModel realm, GroupModel removed) {
        specs(realm).forEach(spec -> {
            if (spec.getGroups() != null && spec.getGroups().remove(removed.getId())) {
                LOG.tracef("Dropping removed group %s from membership of user %s", removed.getId(), spec.getId());
                UserCrStore.save(spec);
            }
        });
    }

    /**
     * Client removal cascade: drop consents and grants referencing the client, and remove its
     * service-account user (usually already gone - Keycloak's client manager removes it first).
     */
    void clientRemoved(RealmModel realm, ClientModel removed) {
        specs(realm).forEach(spec -> {
            if (removed.getId().equals(spec.getServiceAccountClientId())) {
                // through session.users() (the storage manager), so UserPreRemovedEvent fires
                session.users().removeUser(realm, adapt(realm, spec));
                return;
            }
            boolean changed = spec.getConsents() != null
                    && spec.getConsents().removeIf(rep -> removed.getId().equals(rep.getClientId()));
            changed |= spec.getClientRoles() != null && spec.getClientRoles().remove(removed.getId()) != null;
            if (changed) {
                UserCrStore.save(spec);
            }
        });
        if (oid4vcEnabled()) {
            // upstream JPA parity: issued credentials of the removed client are deleted
            VerifiableCredentialCrStore.issuedInRealm(realm.getId()).stream()
                    .filter(vc -> removed.getId().equals(vc.getClientId()))
                    .forEach(vc -> VerifiableCredentialCrStore.deleteIssued(realm.getId(), vc.getId()));
        }
    }

    /**
     * Client-rename cascade: the client id is this store's client id, so it keys the client
     * section of every user's role grants, the consent client reference and the service-account
     * link. Rekey the grant map, rewrite the matching consents and rewrite the service-account
     * link.
     */
    void clientRenamed(RealmModel realm, ClientModel renamed, String newClientId) {
        String oldClientId = renamed.getClientId();
        specs(realm).forEach(spec -> {
            boolean changed = false;
            Map<String, List<String>> byClient = spec.getClientRoles();
            if (byClient != null) {
                List<String> names = byClient.remove(oldClientId);
                if (names != null) {
                    byClient.put(newClientId, names);
                    changed = true;
                }
            }
            if (spec.getConsents() != null) {
                for (UserConsentSpec consent : spec.getConsents()) {
                    if (oldClientId.equals(consent.getClientId())) {
                        consent.setClientId(newClientId);
                        changed = true;
                    }
                }
            }
            if (oldClientId.equals(spec.getServiceAccountClientId())) {
                spec.setServiceAccountClientId(newClientId);
                changed = true;
            }
            if (changed) {
                LOG.tracef("Rewriting renamed client %s to %s in grants of user %s",
                        oldClientId, newClientId, spec.getId());
                UserCrStore.save(spec);
            }
        });
        if (oid4vcEnabled()) {
            // the client id is the clientId in this store; rekey issued verifiable credentials of
            // the renamed client instead of orphaning them
            VerifiableCredentialCrStore.issuedInRealm(realm.getId()).stream()
                    .filter(vc -> oldClientId.equals(vc.getClientId()))
                    .forEach(vc -> {
                        vc.setClientId(newClientId);
                        VerifiableCredentialCrStore.saveIssued(vc);
                    });
        }
    }

    /**
     * Client-scope removal cascade: purge the scope from every consent's granted list, and
     * (upstream JPA parity) delete verifiable credentials bound to the scope together with
     * their issuances.
     */
    void clientScopeRemoved(RealmModel realm, ClientScopeModel removed) {
        specs(realm).forEach(spec -> {
            if (spec.getConsents() == null) {
                return;
            }
            boolean changed = false;
            for (UserConsentSpec consent : spec.getConsents()) {
                if (consent.getGrantedClientScopes() != null
                        && consent.getGrantedClientScopes().remove(removed.getName())) {
                    changed = true;
                }
                if (consent.getGrantedScopeParameters() != null
                        && consent.getGrantedScopeParameters().remove(removed.getName()) != null) {
                    changed = true;
                }
            }
            if (changed) {
                UserCrStore.save(spec);
            }
        });
        if (oid4vcEnabled()) {
            List<UserVerifiableCredentialSpec> scopeCredentials =
                    VerifiableCredentialCrStore.credentialsInRealm(realm.getId()).stream()
                            .filter(vc -> removed.getId().equals(vc.getClientScopeId()))
                            .collect(Collectors.toList());
            if (!scopeCredentials.isEmpty()) {
                Set<String> credentialIds = scopeCredentials.stream()
                        .map(UserVerifiableCredentialSpec::getId)
                        .collect(Collectors.toSet());
                VerifiableCredentialCrStore.issuedInRealm(realm.getId()).stream()
                        .filter(vc -> credentialIds.contains(vc.getVerifiableCredentialId()))
                        .forEach(vc -> VerifiableCredentialCrStore.deleteIssued(realm.getId(), vc.getId()));
                scopeCredentials.forEach(
                        vc -> VerifiableCredentialCrStore.deleteCredential(realm.getId(), vc.getId()));
            }
        }
    }

    /**
     * Client-scope rename cascade: swap the old scope name for the new one in every consent's
     * granted-scope list and granted-scope-parameters map key, preserving list position so the
     * granted-scope ordering stays stable.
     */
    void clientScopeRenamed(RealmModel realm, String oldName, String newName) {
        specs(realm).forEach(spec -> {
            if (spec.getConsents() == null) {
                return;
            }
            boolean changed = false;
            for (UserConsentSpec consent : spec.getConsents()) {
                List<String> granted = consent.getGrantedClientScopes();
                if (granted != null) {
                    int index = granted.indexOf(oldName);
                    if (index >= 0) {
                        granted.set(index, newName);
                        changed = true;
                    }
                }
                Map<String, List<String>> parameters = consent.getGrantedScopeParameters();
                if (parameters != null && parameters.containsKey(oldName)) {
                    parameters.put(newName, parameters.remove(oldName));
                    changed = true;
                }
            }
            if (changed) {
                UserCrStore.save(spec);
            }
        });
        if (oid4vcEnabled()) {
            // the scope id is the scope name in this store; rekey (not delete) verifiable
            // credentials bound to the renamed scope, matching the removal cascade's target
            VerifiableCredentialCrStore.credentialsInRealm(realm.getId()).stream()
                    .filter(vc -> oldName.equals(vc.getClientScopeId()))
                    .forEach(vc -> {
                        vc.setClientScopeId(newName);
                        VerifiableCredentialCrStore.saveCredential(vc);
                    });
        }
    }

    /** Identity-provider removal cascade: drop the alias's links and tokens from every user. */
    void identityProviderRemoved(RealmModel realm, String alias) {
        specs(realm).forEach(spec -> {
            boolean changed = spec.getFederatedIdentities() != null
                    && spec.getFederatedIdentities().removeIf(rep -> alias.equals(rep.getIdentityProvider()));
            if (spec.getFederatedIdentityTokens() != null && spec.getFederatedIdentityTokens().remove(alias) != null) {
                changed = true;
            }
            if (changed) {
                UserCrStore.save(spec);
            }
        });
    }

    @Override
    public void close() {
        knownAdapters.clear();
    }
}
