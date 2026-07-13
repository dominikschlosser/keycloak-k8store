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

import io.github.dominikschlosser.k8store.crd.ClientSessionSpec;
import io.github.dominikschlosser.k8store.crd.UserSessionSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.light.LightweightUserAdapter;

/**
 * {@link UserSessionModel} over a {@link UserSessionSpec}. Every mutation re-persists the owning
 * spec explicitly (a no-op persister for TRANSIENT sessions, which never touch storage); the
 * transaction buffer coalesces the many setter calls of one request into a single server-side
 * apply. Embedded client sessions are pruned on read when they expired or their client is gone.
 */
public class UserSessionAdapter implements UserSessionModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final UserSessionSpec spec;
    private final boolean transientSession;

    UserSessionAdapter(KeycloakSession session, RealmModel realm, UserSessionSpec spec, boolean transientSession) {
        this.session = session;
        this.realm = realm;
        this.spec = spec;
        this.transientSession = transientSession;
    }

    UserSessionSpec getSpec() {
        return spec;
    }

    void persist() {
        if (!transientSession) {
            UserSessionCrStore.save(spec);
        }
    }

    private static int toSeconds(Long millis) {
        return millis == null ? 0 : (int) (millis / 1000L);
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    @Override
    public String getBrokerSessionId() {
        return spec.getBrokerSessionId();
    }

    @Override
    public String getBrokerUserId() {
        return spec.getBrokerUserId();
    }

    @Override
    public UserModel getUser() {
        String userId = spec.getUserId();
        if (userId == null) {
            return null;
        }
        if (LightweightUserAdapter.isLightweightUser(userId)) {
            LightweightUserAdapter user =
                    LightweightUserAdapter.fromString(session, realm, getNote(Constants.SESSION_NOTE_LIGHTWEIGHT_USER));
            user.setUpdateHandler(updated -> setNote(Constants.SESSION_NOTE_LIGHTWEIGHT_USER, updated.serialize()));
            return user;
        }
        return session.users().getUserById(realm, userId);
    }

    @Override
    public String getLoginUsername() {
        return spec.getLoginUsername();
    }

    @Override
    public String getIpAddress() {
        return spec.getIpAddress();
    }

    @Override
    public String getAuthMethod() {
        return spec.getAuthMethod();
    }

    @Override
    public boolean isRememberMe() {
        return Boolean.TRUE.equals(spec.getRememberMe());
    }

    @Override
    public int getStarted() {
        return toSeconds(spec.getStarted());
    }

    @Override
    public int getLastSessionRefresh() {
        return toSeconds(spec.getLastSessionRefresh());
    }

    @Override
    public void setLastSessionRefresh(int seconds) {
        spec.setLastSessionRefresh(seconds * 1000L);
        Expirations.stampUserSession(realm, spec);
        persist();
    }

    @Override
    public boolean isOffline() {
        return Boolean.TRUE.equals(spec.getOffline());
    }

    @Override
    public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() {
        Map<String, AuthenticatedClientSessionModel> result = new HashMap<>();
        Map<String, ClientSessionSpec> clientSessions = spec.getClientSessions();
        if (clientSessions == null) {
            return result;
        }
        long now = Time.currentTimeMillis();
        boolean pruned = false;
        for (Map.Entry<String, ClientSessionSpec> entry : new ArrayList<>(clientSessions.entrySet())) {
            if (Expirations.isClientSessionExpired(entry.getValue(), now)) {
                clientSessions.remove(entry.getKey());
                pruned = true;
                continue;
            }
            ClientModel client = session.clients().getClientById(realm, entry.getKey());
            if (client == null) {
                clientSessions.remove(entry.getKey());
                pruned = true;
                continue;
            }
            result.put(entry.getKey(), new ClientSessionAdapter(session, realm, client, this, entry.getValue()));
        }
        if (pruned) {
            persist();
        }
        return result;
    }

    /** Adds (or replaces) a client session entry and persists the whole session. */
    ClientSessionAdapter putClientSession(ClientModel client, ClientSessionSpec clientSession) {
        if (spec.getClientSessions() == null) {
            spec.setClientSessions(new HashMap<>());
        }
        spec.getClientSessions().put(client.getId(), clientSession);
        persist();
        return new ClientSessionAdapter(session, realm, client, this, clientSession);
    }

    void removeClientSession(String clientStorageId) {
        Map<String, ClientSessionSpec> clientSessions = spec.getClientSessions();
        if (clientSessions != null && clientSessions.remove(clientStorageId) != null) {
            persist();
        }
    }

    @Override
    public void removeAuthenticatedClientSessions(Collection<String> removedClientUuids) {
        Map<String, ClientSessionSpec> clientSessions = spec.getClientSessions();
        if (clientSessions == null || removedClientUuids == null) {
            return;
        }
        boolean changed = false;
        for (String clientUuid : removedClientUuids) {
            changed |= clientSessions.remove(clientUuid) != null;
        }
        if (changed) {
            persist();
        }
    }

    @Override
    public String getNote(String name) {
        return spec.getNotes() == null ? null : spec.getNotes().get(name);
    }

    @Override
    public void setNote(String name, String value) {
        if (value == null) {
            removeNote(name);
            return;
        }
        if (spec.getNotes() == null) {
            spec.setNotes(new HashMap<>());
        }
        spec.getNotes().put(name, value);
        persist();
    }

    @Override
    public void removeNote(String name) {
        if (spec.getNotes() != null && spec.getNotes().remove(name) != null) {
            persist();
        }
    }

    @Override
    public Map<String, String> getNotes() {
        return spec.getNotes() == null ? new HashMap<>() : new HashMap<>(spec.getNotes());
    }

    @Override
    public State getState() {
        return spec.getState() == null ? null : State.valueOf(spec.getState());
    }

    @Override
    public void setState(State state) {
        spec.setState(state == null ? null : state.name());
        persist();
    }

    @Override
    public void restartSession(
            RealmModel realm,
            UserModel user,
            String loginUsername,
            String ipAddress,
            String authMethod,
            boolean rememberMe,
            String brokerSessionId,
            String brokerUserId) {
        long now = Time.currentTimeMillis();
        spec.setUserId(user == null ? null : user.getId());
        spec.setLoginUsername(loginUsername);
        spec.setIpAddress(ipAddress);
        spec.setAuthMethod(authMethod);
        spec.setRememberMe(rememberMe ? Boolean.TRUE : null);
        spec.setBrokerSessionId(brokerSessionId);
        spec.setBrokerUserId(brokerUserId);
        spec.setStarted(now);
        spec.setLastSessionRefresh(now);
        spec.setState(null);
        spec.setNotes(null);
        spec.setClientSessions(null);
        Expirations.stampUserSession(realm, spec);
        persist();
    }

    @Override
    public SessionPersistenceState getPersistenceState() {
        return transientSession ? SessionPersistenceState.TRANSIENT : SessionPersistenceState.PERSISTENT;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserSessionModel)) {
            return false;
        }
        return Objects.equals(getId(), ((UserSessionModel) other).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }
}
