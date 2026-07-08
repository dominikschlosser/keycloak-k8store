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
package com.github.dominikschlosser.k8store.authsession;

import com.github.dominikschlosser.k8store.crd.AuthTabSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.light.LightweightUserAdapter;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.CommonClientSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * {@link AuthenticationSessionModel} over one {@link AuthTabSpec} embedded in its root session's
 * spec. Every mutation re-persists the root spec through the parent adapter; the transaction
 * buffer coalesces the flood of note/status updates of one flow step into a single server-side
 * apply.
 */
public class AuthSessionAdapter implements AuthenticationSessionModel {

    private final KeycloakSession session;
    private final RootAuthSessionAdapter parent;
    private final String tabId;
    private final AuthTabSpec spec;

    AuthSessionAdapter(KeycloakSession session, RootAuthSessionAdapter parent, String tabId, AuthTabSpec spec) {
        this.session = session;
        this.parent = parent;
        this.tabId = tabId;
        this.spec = spec;
    }

    private void persist() {
        parent.persist();
    }

    @Override
    public String getTabId() {
        return tabId;
    }

    @Override
    public RootAuthenticationSessionModel getParentSession() {
        return parent;
    }

    @Override
    public RealmModel getRealm() {
        return parent.getRealm();
    }

    @Override
    public ClientModel getClient() {
        return session.clients().getClientById(parent.getRealm(), spec.getClientId());
    }

    @Override
    public String getRedirectUri() {
        return spec.getRedirectUri();
    }

    @Override
    public void setRedirectUri(String uri) {
        spec.setRedirectUri(uri);
        persist();
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

    @Override
    public Map<String, CommonClientSessionModel.ExecutionStatus> getExecutionStatus() {
        Map<String, CommonClientSessionModel.ExecutionStatus> result = new LinkedHashMap<>();
        if (spec.getExecutionStatus() != null) {
            spec.getExecutionStatus()
                    .forEach((executionId, status) ->
                            result.put(executionId, CommonClientSessionModel.ExecutionStatus.valueOf(status)));
        }
        return result;
    }

    @Override
    public void setExecutionStatus(String authenticator, CommonClientSessionModel.ExecutionStatus status) {
        if (spec.getExecutionStatus() == null) {
            spec.setExecutionStatus(new HashMap<>());
        }
        spec.getExecutionStatus().put(authenticator, status.name());
        persist();
    }

    @Override
    public void clearExecutionStatus() {
        if (spec.getExecutionStatus() != null) {
            spec.setExecutionStatus(null);
            persist();
        }
    }

    @Override
    public UserModel getAuthenticatedUser() {
        String userId = spec.getAuthUserId();
        if (userId == null) {
            return null;
        }
        if (LightweightUserAdapter.isLightweightUser(userId)) {
            LightweightUserAdapter user = LightweightUserAdapter.fromString(
                    session, parent.getRealm(), getUserSessionNotes().get(Constants.SESSION_NOTE_LIGHTWEIGHT_USER));
            user.setUpdateHandler(
                    updated -> setUserSessionNote(Constants.SESSION_NOTE_LIGHTWEIGHT_USER, updated.serialize()));
            return user;
        }
        return session.users().getUserById(parent.getRealm(), userId);
    }

    @Override
    public void setAuthenticatedUser(UserModel user) {
        if (user == null) {
            spec.setAuthUserId(null);
            if (spec.getUserSessionNotes() != null) {
                spec.getUserSessionNotes().remove(Constants.SESSION_NOTE_LIGHTWEIGHT_USER);
            }
            persist();
            return;
        }
        spec.setAuthUserId(user.getId());
        if (LightweightUserAdapter.isLightweightUser(user)) {
            LightweightUserAdapter lightweight = (LightweightUserAdapter) user;
            setUserSessionNote(Constants.SESSION_NOTE_LIGHTWEIGHT_USER, lightweight.serialize());
            lightweight.setUpdateHandler(
                    updated -> setUserSessionNote(Constants.SESSION_NOTE_LIGHTWEIGHT_USER, updated.serialize()));
        }
        persist();
    }

    @Override
    public Set<String> getRequiredActions() {
        return spec.getRequiredActions() == null ? new HashSet<>() : new HashSet<>(spec.getRequiredActions());
    }

    @Override
    public void addRequiredAction(String action) {
        if (spec.getRequiredActions() == null) {
            spec.setRequiredActions(new ArrayList<>());
        }
        if (!spec.getRequiredActions().contains(action)) {
            spec.getRequiredActions().add(action);
            persist();
        }
    }

    @Override
    public void removeRequiredAction(String action) {
        if (spec.getRequiredActions() != null && spec.getRequiredActions().remove(action)) {
            persist();
        }
    }

    @Override
    public void addRequiredAction(UserModel.RequiredAction action) {
        addRequiredAction(action.name());
    }

    @Override
    public void removeRequiredAction(UserModel.RequiredAction action) {
        removeRequiredAction(action.name());
    }

    @Override
    public void setUserSessionNote(String name, String value) {
        if (spec.getUserSessionNotes() == null) {
            spec.setUserSessionNotes(new HashMap<>());
        }
        spec.getUserSessionNotes().put(name, value);
        persist();
    }

    @Override
    public Map<String, String> getUserSessionNotes() {
        return spec.getUserSessionNotes() == null ? new HashMap<>() : new HashMap<>(spec.getUserSessionNotes());
    }

    @Override
    public void clearUserSessionNotes() {
        if (spec.getUserSessionNotes() != null) {
            spec.setUserSessionNotes(null);
            persist();
        }
    }

    @Override
    public String getAuthNote(String name) {
        return spec.getAuthNotes() == null ? null : spec.getAuthNotes().get(name);
    }

    @Override
    public void setAuthNote(String name, String value) {
        if (spec.getAuthNotes() == null) {
            spec.setAuthNotes(new HashMap<>());
        }
        spec.getAuthNotes().put(name, value);
        persist();
    }

    @Override
    public void removeAuthNote(String name) {
        if (spec.getAuthNotes() != null && spec.getAuthNotes().remove(name) != null) {
            persist();
        }
    }

    @Override
    public void clearAuthNotes() {
        if (spec.getAuthNotes() != null) {
            spec.setAuthNotes(null);
            persist();
        }
    }

    @Override
    public String getClientNote(String name) {
        return spec.getClientNotes() == null ? null : spec.getClientNotes().get(name);
    }

    @Override
    public void setClientNote(String name, String value) {
        if (spec.getClientNotes() == null) {
            spec.setClientNotes(new HashMap<>());
        }
        spec.getClientNotes().put(name, value);
        persist();
    }

    @Override
    public void removeClientNote(String name) {
        if (spec.getClientNotes() != null && spec.getClientNotes().remove(name) != null) {
            persist();
        }
    }

    @Override
    public Map<String, String> getClientNotes() {
        return spec.getClientNotes() == null ? new HashMap<>() : new HashMap<>(spec.getClientNotes());
    }

    @Override
    public void clearClientNotes() {
        if (spec.getClientNotes() != null) {
            spec.setClientNotes(null);
            persist();
        }
    }

    @Override
    public Set<String> getClientScopes() {
        return spec.getClientScopes() == null ? new HashSet<>() : new HashSet<>(spec.getClientScopes());
    }

    @Override
    public void setClientScopes(Set<String> clientScopes) {
        spec.setClientScopes(clientScopes == null ? null : new ArrayList<>(clientScopes));
        persist();
    }
}
