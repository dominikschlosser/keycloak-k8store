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

import com.github.dominikschlosser.k8store.crd.AuthSessionSpec;
import com.github.dominikschlosser.k8store.crd.AuthTabSpec;
import java.util.HashMap;
import java.util.Map;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * {@link AuthenticationSessionProvider} serving root authentication sessions from
 * {@code KeycloakAuthSession} custom resources (one CR per root session, tabs embedded).
 * Expiration follows the realm's login timeout settings and is enforced by the storage backend
 * (read filtering plus background reaper), so {@code removeAllExpired} is a no-op. Since every
 * node mirrors the same CRs, {@code updateNonlocalSessionAuthNotes} is an ordinary CR update.
 */
public class AuthSessionCrProvider implements AuthenticationSessionProvider {

    private final KeycloakSession session;

    /** Adapter per root session id, so repeated lookups return the instance already mutated. */
    private final Map<String, RootAuthSessionAdapter> knownAdapters = new HashMap<>();

    public AuthSessionCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private RootAuthSessionAdapter adapt(RealmModel realm, AuthSessionSpec spec) {
        return knownAdapters.computeIfAbsent(
                spec.getId(), id -> new RootAuthSessionAdapter(session, this, realm, spec));
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm) {
        return createRootAuthenticationSession(realm, KeycloakModelUtils.generateId());
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm, String id) {
        String rootId = id != null ? id : KeycloakModelUtils.generateId();
        if (AuthSessionCrStore.read(realm.getId(), rootId) != null) {
            throw new ModelDuplicateException("Root authentication session exists: " + rootId);
        }
        AuthSessionSpec spec = new AuthSessionSpec();
        spec.setId(rootId);
        spec.setRealm(realm.getId());
        spec.setTimestamp(Time.currentTimeMillis());
        spec.setExpiresAt(SessionExpiration.getAuthSessionExpiration(realm, Time.currentTime()) * 1000L);
        AuthSessionCrStore.save(spec);
        RootAuthSessionAdapter adapter = new RootAuthSessionAdapter(session, this, realm, spec);
        knownAdapters.put(rootId, adapter);
        return adapter;
    }

    @Override
    public RootAuthenticationSessionModel getRootAuthenticationSession(RealmModel realm, String authSessionId) {
        if (authSessionId == null) {
            return null;
        }
        AuthSessionSpec spec = AuthSessionCrStore.read(realm.getId(), authSessionId);
        return spec == null ? null : adapt(realm, spec);
    }

    @Override
    public void removeRootAuthenticationSession(RealmModel realm, RootAuthenticationSessionModel authSession) {
        if (authSession == null) {
            return;
        }
        knownAdapters.remove(authSession.getId());
        AuthSessionCrStore.delete(realm.getId(), authSession.getId());
    }

    /**
     * Retargets embedded tabs when a client's clientId changes. A tab records its client by
     * clientId (this store's client id); a rename would otherwise leave the tab pointing at the
     * old id, so the flow's tab lookup ({@code client.getId()} vs {@code tab.getClientId()} in
     * {@link RootAuthSessionAdapter#getAuthenticationSession}) fails and the in-flight login for
     * that client aborts. Mirrors the user-session rekey, driven off the k8store
     * {@code CLIENT_RENAMED} event (Keycloak has no upstream client-rename hook for sessions).
     */
    public void onClientRenamed(RealmModel realm, String oldClientId, String newClientId) {
        if (oldClientId == null || newClientId == null || oldClientId.equals(newClientId)) {
            return;
        }
        for (AuthSessionSpec spec : AuthSessionCrStore.allInRealm(realm.getId())) {
            Map<String, AuthTabSpec> tabs = spec.getTabs();
            if (tabs == null) {
                continue;
            }
            boolean changed = false;
            for (AuthTabSpec tab : tabs.values()) {
                if (oldClientId.equals(tab.getClientId())) {
                    tab.setClientId(newClientId);
                    changed = true;
                }
            }
            if (changed) {
                // drop any adapter memoized on the pre-rename spec, so a later persist in the same
                // request cannot re-write the old clientId from its stale copy
                knownAdapters.remove(spec.getId());
                AuthSessionCrStore.save(spec);
            }
        }
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
        AuthSessionCrStore.allInRealm(realm.getId())
                .forEach(spec -> AuthSessionCrStore.delete(realm.getId(), spec.getId()));
        knownAdapters.values().removeIf(adapter -> realm.getId()
                .equals(adapter.getSpec().getRealm()));
    }

    @Override
    public void updateNonlocalSessionAuthNotes(
            AuthenticationSessionCompoundId compoundId, Map<String, String> authNotesFragment) {
        if (compoundId == null || authNotesFragment == null) {
            return;
        }
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return;
        }
        // every node mirrors the CRs, so "nonlocal" degenerates to a plain lookup + update
        RootAuthenticationSessionModel root = getRootAuthenticationSession(realm, compoundId.getRootSessionId());
        if (root == null) {
            return;
        }
        RootAuthSessionAdapter adapter = (RootAuthSessionAdapter) root;
        Map<String, AuthTabSpec> tabs = adapter.getSpec().getTabs();
        AuthTabSpec tab = tabs == null ? null : tabs.get(compoundId.getTabId());
        if (tab == null
                || (compoundId.getClientUUID() != null
                        && !compoundId.getClientUUID().equals(tab.getClientId()))) {
            return;
        }
        if (tab.getAuthNotes() == null) {
            tab.setAuthNotes(new HashMap<>());
        }
        tab.getAuthNotes().putAll(authNotesFragment);
        adapter.persist();
    }

    @Override
    public void close() {
        knownAdapters.clear();
    }
}
