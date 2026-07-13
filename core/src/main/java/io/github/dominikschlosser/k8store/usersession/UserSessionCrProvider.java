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
package io.github.dominikschlosser.k8store.usersession;

import static org.keycloak.utils.StreamsUtil.paginatedStream;

import io.github.dominikschlosser.k8store.crd.ClientSessionSpec;
import io.github.dominikschlosser.k8store.crd.UserSessionSpec;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * {@link UserSessionProvider} serving user sessions from {@code KeycloakUserSession} custom
 * resources (client sessions embedded in the session spec).
 *
 * <p>Semantics mirrored from Keycloak's built-in stores: online and offline sessions are
 * separate entities of the same kind ({@code offline} flag), linked bidirectionally through the
 * {@code correspondingSessionId} note; TRANSIENT sessions live only in this provider's memory
 * and never reach storage; expiration is stamped on every timestamp change and enforced by the
 * storage backend (read filtering plus background reaper), so {@code removeAllExpired} is a
 * no-op. The provider memoizes adapters per id so a request keeps mutating one instance.
 */
public class UserSessionCrProvider implements UserSessionProvider {

    private final KeycloakSession session;

    /** TRANSIENT sessions: per-request in-memory only, never persisted. */
    private final Map<String, UserSessionAdapter> transientSessions = new HashMap<>();

    /** Adapter per session id, so repeated lookups return the instance already being mutated. */
    private final Map<String, UserSessionAdapter> knownAdapters = new HashMap<>();

    public UserSessionCrProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public KeycloakSession getKeycloakSession() {
        return session;
    }

    private UserSessionAdapter adapt(RealmModel realm, UserSessionSpec spec) {
        return knownAdapters.computeIfAbsent(spec.getId(), id -> new UserSessionAdapter(session, realm, spec, false));
    }

    private Stream<UserSessionSpec> specs(RealmModel realm, boolean offline) {
        return UserSessionCrStore.allInRealm(realm.getId()).stream()
                .filter(spec -> offline == Boolean.TRUE.equals(spec.getOffline()));
    }

    private Stream<UserSessionModel> sorted(RealmModel realm, Stream<UserSessionSpec> specs) {
        return specs.sorted(Comparator.comparing(
                                UserSessionSpec::getStarted, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(UserSessionSpec::getId))
                .map(spec -> (UserSessionModel) adapt(realm, spec));
    }

    private static boolean hasLiveClientSession(UserSessionSpec spec, String clientStorageId, long now) {
        Map<String, ClientSessionSpec> clientSessions = spec.getClientSessions();
        if (clientSessions == null) {
            return false;
        }
        ClientSessionSpec clientSession = clientSessions.get(clientStorageId);
        return clientSession != null && !Expirations.isClientSessionExpired(clientSession, now);
    }

    // ------------------------------------------------------------------ create

    @Override
    public UserSessionModel createUserSession(
            String id,
            RealmModel realm,
            UserModel user,
            String loginUsername,
            String ipAddress,
            String authMethod,
            boolean rememberMe,
            String brokerSessionId,
            String brokerUserId,
            UserSessionModel.SessionPersistenceState persistenceState) {
        String sessionId = id != null ? id : KeycloakModelUtils.generateId();
        long now = Time.currentTimeMillis();
        UserSessionSpec spec = new UserSessionSpec();
        spec.setId(sessionId);
        spec.setRealm(realm.getId());
        spec.setUserId(user.getId());
        spec.setLoginUsername(loginUsername);
        spec.setIpAddress(ipAddress);
        spec.setAuthMethod(authMethod);
        spec.setRememberMe(rememberMe ? Boolean.TRUE : null);
        spec.setBrokerSessionId(brokerSessionId);
        spec.setBrokerUserId(brokerUserId);
        spec.setStarted(now);
        spec.setLastSessionRefresh(now);
        Expirations.stampUserSession(realm, spec);

        if (persistenceState == UserSessionModel.SessionPersistenceState.TRANSIENT) {
            UserSessionAdapter adapter = new UserSessionAdapter(session, realm, spec, true);
            transientSessions.put(sessionId, adapter);
            return adapter;
        }
        UserSessionCrStore.save(spec);
        UserSessionAdapter adapter = new UserSessionAdapter(session, realm, spec, false);
        knownAdapters.put(sessionId, adapter);
        return adapter;
    }

    @Override
    public AuthenticatedClientSessionModel createClientSession(
            RealmModel realm, ClientModel client, UserSessionModel userSession) {
        UserSessionAdapter parent = requireAdapter(userSession);
        long now = Time.currentTimeMillis();
        ClientSessionSpec clientSession = new ClientSessionSpec();
        clientSession.setId(KeycloakModelUtils.generateId());
        clientSession.setClientId(client.getId());
        clientSession.setTimestamp(now);
        Map<String, String> notes = new HashMap<>();
        notes.put(AuthenticatedClientSessionModel.STARTED_AT_NOTE, String.valueOf((int) (now / 1000L)));
        notes.put(
                AuthenticatedClientSessionModel.USER_SESSION_STARTED_AT_NOTE, String.valueOf(userSession.getStarted()));
        if (userSession.isRememberMe()) {
            notes.put(AuthenticatedClientSessionModel.USER_SESSION_REMEMBER_ME_NOTE, "true");
        }
        clientSession.setNotes(notes);
        Expirations.stampClientSession(realm, client, parent.getSpec(), clientSession);
        return parent.putClientSession(client, clientSession);
    }

    /** The adapters this provider hands out are the only user-session models it can persist. */
    private UserSessionAdapter requireAdapter(UserSessionModel userSession) {
        if (userSession instanceof UserSessionAdapter adapter) {
            return adapter;
        }
        UserSessionAdapter known = userSession == null
                ? null
                : knownAdapters.getOrDefault(userSession.getId(), transientSessions.get(userSession.getId()));
        if (known == null) {
            throw new IllegalStateException("Unknown user session model type: "
                    + (userSession == null ? "null" : userSession.getClass().getName()));
        }
        return known;
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
        if (id == null) {
            return null;
        }
        UserSessionAdapter transientSession = transientSessions.get(id);
        if (transientSession != null) {
            return transientSession;
        }
        UserSessionSpec spec = UserSessionCrStore.read(realm.getId(), id);
        if (spec == null || Boolean.TRUE.equals(spec.getOffline())) {
            return null;
        }
        return adapt(realm, spec);
    }

    @Override
    public AuthenticatedClientSessionModel getClientSession(
            UserSessionModel userSession, ClientModel client, boolean offline) {
        if (userSession == null || client == null || userSession.isOffline() != offline) {
            return null;
        }
        return userSession.getAuthenticatedClientSessions().get(client.getId());
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
        return sorted(realm, specs(realm, false).filter(spec -> user.getId().equals(spec.getUserId())));
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client) {
        long now = Time.currentTimeMillis();
        return sorted(realm, specs(realm, false).filter(spec -> hasLiveClientSession(spec, client.getId(), now)));
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(
            RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        return paginatedStream(getUserSessionsStream(realm, client), firstResult, maxResults);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return sorted(realm, specs(realm, false).filter(spec -> brokerUserId.equals(spec.getBrokerUserId())));
    }

    @Override
    public UserSessionModel getUserSessionByBrokerSessionId(RealmModel realm, String brokerSessionId) {
        return specs(realm, false)
                .filter(spec -> brokerSessionId.equals(spec.getBrokerSessionId()))
                .findFirst()
                .map(spec -> (UserSessionModel) adapt(realm, spec))
                .orElse(null);
    }

    @Override
    public UserSessionModel getUserSessionWithPredicate(
            RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
        UserSessionModel userSession = offline ? getOfflineUserSession(realm, id) : getUserSession(realm, id);
        return userSession != null && predicate.test(userSession) ? userSession : null;
    }

    @Override
    public long getActiveUserSessions(RealmModel realm, ClientModel client) {
        long now = Time.currentTimeMillis();
        return specs(realm, false)
                .filter(spec -> hasLiveClientSession(spec, client.getId(), now))
                .count();
    }

    @Override
    public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
        long now = Time.currentTimeMillis();
        return specs(realm, offline)
                .flatMap(spec -> spec.getClientSessions() == null
                        ? Stream.<String>empty()
                        : spec.getClientSessions().entrySet().stream()
                                .filter(entry -> !Expirations.isClientSessionExpired(entry.getValue(), now))
                                .map(Map.Entry::getKey))
                .collect(Collectors.groupingBy(clientId -> clientId, Collectors.counting()));
    }

    // ------------------------------------------------------------------ removal

    @Override
    public void removeUserSession(RealmModel realm, UserSessionModel userSession) {
        if (userSession == null) {
            return;
        }
        if (transientSessions.remove(userSession.getId()) != null) {
            return;
        }
        knownAdapters.remove(userSession.getId());
        UserSessionCrStore.delete(realm.getId(), userSession.getId());
    }

    @Override
    public void removeUserSessions(RealmModel realm, UserModel user) {
        specs(realm, false)
                .filter(spec -> user.getId().equals(spec.getUserId()))
                .forEach(spec -> {
                    knownAdapters.remove(spec.getId());
                    UserSessionCrStore.delete(realm.getId(), spec.getId());
                });
    }

    @Override
    public void removeUserSessions(RealmModel realm) {
        specs(realm, false).forEach(spec -> {
            knownAdapters.remove(spec.getId());
            UserSessionCrStore.delete(realm.getId(), spec.getId());
        });
    }

    /**
     * Removes all of a deleted user's sessions, both online and offline. The public
     * {@code removeUserSessions(realm, user)} covers only online sessions, so a deleted user's
     * offline sessions would otherwise survive as CRs until they expire.
     */
    public void onUserRemoved(RealmModel realm, UserModel user) {
        UserSessionCrStore.allInRealm(realm.getId()).stream()
                .filter(spec -> user.getId().equals(spec.getUserId()))
                .forEach(spec -> {
                    knownAdapters.remove(spec.getId());
                    UserSessionCrStore.delete(realm.getId(), spec.getId());
                });
    }

    @Override
    public void removeAllExpired() {
        // the storage backend filters expired sessions on read and reaps their CRs
    }

    @Override
    public void removeExpired(RealmModel realm) {
        // see removeAllExpired
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        UserSessionCrStore.allInRealm(realm.getId())
                .forEach(spec -> UserSessionCrStore.delete(realm.getId(), spec.getId()));
        transientSessions.values().removeIf(adapter -> realm.getId()
                .equals(adapter.getSpec().getRealm()));
        knownAdapters.values().removeIf(adapter -> realm.getId()
                .equals(adapter.getSpec().getRealm()));
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        for (UserSessionSpec spec : UserSessionCrStore.allInRealm(realm.getId())) {
            Map<String, ClientSessionSpec> clientSessions = spec.getClientSessions();
            if (clientSessions != null && clientSessions.remove(client.getId()) != null) {
                // drop any adapter memoized on the pre-edit spec, so a later persist in the same
                // request cannot re-add the removed client session from its stale copy
                knownAdapters.remove(spec.getId());
                UserSessionCrStore.save(spec);
            }
        }
    }

    /**
     * Rekeys embedded client sessions when a client's clientId changes. Client sessions are keyed
     * by clientId (this store's client id), so a rename would otherwise orphan them - the next
     * read cannot resolve the old key to a client and silently drops the entry. Client removal has
     * the {@code onClientRemoved} SPI hook; a clientId rename has no upstream hook (Keycloak keys
     * client sessions by a stable internal id), so this store drives it off the k8store
     * {@code CLIENT_RENAMED} event, mirroring the removal cleanup above.
     */
    public void onClientRenamed(RealmModel realm, String oldClientId, String newClientId) {
        if (oldClientId == null || newClientId == null || oldClientId.equals(newClientId)) {
            return;
        }
        for (UserSessionSpec spec : UserSessionCrStore.allInRealm(realm.getId())) {
            Map<String, ClientSessionSpec> clientSessions = spec.getClientSessions();
            ClientSessionSpec clientSession = clientSessions == null ? null : clientSessions.remove(oldClientId);
            if (clientSession != null) {
                clientSession.setClientId(newClientId);
                clientSessions.put(newClientId, clientSession);
                // drop any adapter memoized on the pre-rename spec, so a later persist in the same
                // request cannot re-write the old clientId key from its stale copy
                knownAdapters.remove(spec.getId());
                UserSessionCrStore.save(spec);
            }
        }
    }

    // ------------------------------------------------------------------ offline sessions

    @Override
    public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
        RealmModel realm = userSession.getRealm();
        String linkedId = userSession.getNote(UserSessionModel.CORRESPONDING_SESSION_ID);
        if (linkedId != null) {
            UserSessionModel existing = getOfflineUserSession(realm, linkedId);
            if (existing != null) {
                return existing;
            }
        }
        long now = Time.currentTimeMillis();
        UserSessionSpec offline = new UserSessionSpec();
        offline.setId(KeycloakModelUtils.generateId());
        offline.setRealm(realm.getId());
        offline.setUserId(
                userSession.getUser() == null ? null : userSession.getUser().getId());
        offline.setLoginUsername(userSession.getLoginUsername());
        offline.setIpAddress(userSession.getIpAddress());
        offline.setAuthMethod(userSession.getAuthMethod());
        offline.setRememberMe(userSession.isRememberMe() ? Boolean.TRUE : null);
        offline.setBrokerSessionId(userSession.getBrokerSessionId());
        offline.setBrokerUserId(userSession.getBrokerUserId());
        offline.setOffline(true);
        offline.setStarted(now);
        offline.setLastSessionRefresh(now);
        Map<String, String> notes = new HashMap<>(userSession.getNotes());
        notes.put(UserSessionModel.CORRESPONDING_SESSION_ID, userSession.getId());
        offline.setNotes(notes);
        Expirations.stampUserSession(realm, offline);
        UserSessionCrStore.save(offline);
        userSession.setNote(UserSessionModel.CORRESPONDING_SESSION_ID, offline.getId());
        return adapt(realm, offline);
    }

    @Override
    public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
        if (userSessionId == null) {
            return null;
        }
        UserSessionSpec spec = UserSessionCrStore.read(realm.getId(), userSessionId);
        if (spec != null && Boolean.TRUE.equals(spec.getOffline())) {
            return adapt(realm, spec);
        }
        // the caller may hold the online session's id: follow the note-based linkage
        return specs(realm, true)
                .filter(offline -> offline.getNotes() != null
                        && userSessionId.equals(offline.getNotes().get(UserSessionModel.CORRESPONDING_SESSION_ID)))
                .findFirst()
                .map(offline -> (UserSessionModel) adapt(realm, offline))
                .orElse(null);
    }

    @Override
    public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
        if (userSession == null) {
            return;
        }
        UserSessionModel offline =
                userSession.isOffline() ? userSession : getOfflineUserSession(realm, userSession.getId());
        if (offline == null) {
            return;
        }
        String onlineId = offline.getNote(UserSessionModel.CORRESPONDING_SESSION_ID);
        knownAdapters.remove(offline.getId());
        UserSessionCrStore.delete(realm.getId(), offline.getId());
        if (onlineId != null) {
            UserSessionModel online = getUserSession(realm, onlineId);
            if (online != null) {
                online.removeNote(UserSessionModel.CORRESPONDING_SESSION_ID);
            }
        }
    }

    @Override
    public AuthenticatedClientSessionModel createOfflineClientSession(
            AuthenticatedClientSessionModel clientSession, UserSessionModel offlineUserSession) {
        UserSessionAdapter parent = requireAdapter(offlineUserSession);
        RealmModel realm = clientSession.getRealm();
        ClientModel client = clientSession.getClient();
        long now = Time.currentTimeMillis();
        ClientSessionSpec offline = new ClientSessionSpec();
        offline.setId(KeycloakModelUtils.generateId());
        offline.setClientId(client.getId());
        offline.setTimestamp(now);
        offline.setAction(clientSession.getAction());
        offline.setProtocol(clientSession.getProtocol());
        offline.setRedirectUri(clientSession.getRedirectUri());
        Map<String, String> notes = new HashMap<>(clientSession.getNotes());
        notes.put(AuthenticatedClientSessionModel.STARTED_AT_NOTE, String.valueOf((int) (now / 1000L)));
        notes.put(
                AuthenticatedClientSessionModel.USER_SESSION_STARTED_AT_NOTE,
                String.valueOf(offlineUserSession.getStarted()));
        offline.setNotes(notes);
        Expirations.stampClientSession(realm, client, parent.getSpec(), offline);
        return parent.putClientSession(client, offline);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
        return sorted(realm, specs(realm, true).filter(spec -> user.getId().equals(spec.getUserId())));
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return sorted(realm, specs(realm, true).filter(spec -> brokerUserId.equals(spec.getBrokerUserId())));
    }

    @Override
    public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
        long now = Time.currentTimeMillis();
        return specs(realm, true)
                .filter(spec -> hasLiveClientSession(spec, client.getId(), now))
                .count();
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(
            RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        long now = Time.currentTimeMillis();
        return paginatedStream(
                sorted(realm, specs(realm, true).filter(spec -> hasLiveClientSession(spec, client.getId(), now))),
                firstResult,
                maxResults);
    }

    // ------------------------------------------------------------------ misc

    @Override
    public int getStartupTime(RealmModel realm) {
        return (int) (session.getKeycloakSessionFactory().getServerStartupTimestamp() / 1000L);
    }

    @Override
    public void close() {
        transientSessions.clear();
        knownAdapters.clear();
    }
}
