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
import java.util.HashMap;
import java.util.Map;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;

/**
 * {@link AuthenticatedClientSessionModel} over a {@link ClientSessionSpec} embedded in its user
 * session. Every mutation re-persists the owning user-session spec through the parent adapter -
 * client sessions have no CR of their own. Refresh-token bookkeeping goes through the interface's
 * default note-based methods, which land in this spec's notes.
 */
public class ClientSessionAdapter implements AuthenticatedClientSessionModel {

    private final RealmModel realm;
    private final ClientModel client;
    private final UserSessionAdapter userSession;
    private final ClientSessionSpec spec;
    private boolean detached;

    ClientSessionAdapter(
            KeycloakSession session,
            RealmModel realm,
            ClientModel client,
            UserSessionAdapter userSession,
            ClientSessionSpec spec) {
        this.realm = realm;
        this.client = client;
        this.userSession = userSession;
        this.spec = spec;
    }

    private void persist() {
        if (!detached) {
            userSession.persist();
        }
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public int getTimestamp() {
        return spec.getTimestamp() == null ? 0 : (int) (spec.getTimestamp() / 1000L);
    }

    @Override
    public void setTimestamp(int seconds) {
        spec.setTimestamp(seconds * 1000L);
        Expirations.stampClientSession(realm, client, userSession.getSpec(), spec);
        persist();
    }

    @Override
    public void detachFromUserSession() {
        userSession.removeClientSession(client.getId());
        detached = true;
    }

    @Override
    public UserSessionModel getUserSession() {
        return detached ? null : userSession;
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
    public String getRedirectUri() {
        return spec.getRedirectUri();
    }

    @Override
    public void setRedirectUri(String redirectUri) {
        spec.setRedirectUri(redirectUri);
        persist();
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    @Override
    public ClientModel getClient() {
        return client;
    }

    @Override
    public String getAction() {
        return spec.getAction();
    }

    @Override
    public void setAction(String action) {
        spec.setAction(action);
        persist();
    }

    @Override
    public String getProtocol() {
        return spec.getProtocol();
    }

    @Override
    public void setProtocol(String protocol) {
        spec.setProtocol(protocol);
        persist();
    }
}
