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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * {@link RootAuthenticationSessionModel} over an {@link AuthSessionSpec}. All browser tabs are
 * embedded in the one spec; creating or removing a tab refreshes the root's timestamp and
 * expiration (the whole in-flight login stays alive while any tab makes progress) and
 * re-persists the spec. A per-root tab limit evicts the oldest tab, bounding the CR size against
 * tab-spamming clients.
 */
public class RootAuthSessionAdapter implements RootAuthenticationSessionModel {

    /** Same default as Keycloak's built-in authentication session stores. */
    static final int TAB_LIMIT = 300;

    private final KeycloakSession session;
    private final AuthSessionCrProvider provider;
    private final RealmModel realm;
    private final AuthSessionSpec spec;

    RootAuthSessionAdapter(KeycloakSession session, AuthSessionCrProvider provider,
                           RealmModel realm, AuthSessionSpec spec) {
        this.session = session;
        this.provider = provider;
        this.realm = realm;
        this.spec = spec;
    }

    AuthSessionSpec getSpec() {
        return spec;
    }

    void persist() {
        AuthSessionCrStore.save(spec);
    }

    private void refreshTimestamp() {
        int nowSeconds = Time.currentTime();
        spec.setTimestamp(Time.currentTimeMillis());
        spec.setExpiresAt(SessionExpiration.getAuthSessionExpiration(realm, nowSeconds) * 1000L);
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
    public int getTimestamp() {
        return spec.getTimestamp() == null ? 0 : (int) (spec.getTimestamp() / 1000L);
    }

    @Override
    public void setTimestamp(int seconds) {
        spec.setTimestamp(seconds * 1000L);
        spec.setExpiresAt(SessionExpiration.getAuthSessionExpiration(realm, seconds) * 1000L);
        persist();
    }

    @Override
    public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
        Map<String, AuthenticationSessionModel> result = new LinkedHashMap<>();
        if (spec.getTabs() != null) {
            spec.getTabs().forEach((tabId, tab) -> result.put(tabId,
                    new AuthSessionAdapter(session, this, tabId, tab)));
        }
        return result;
    }

    @Override
    public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
        if (client == null || tabId == null || spec.getTabs() == null) {
            return null;
        }
        AuthTabSpec tab = spec.getTabs().get(tabId);
        if (tab == null || !client.getId().equals(tab.getClientId())) {
            return null;
        }
        return new AuthSessionAdapter(session, this, tabId, tab);
    }

    @Override
    public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
        if (spec.getTabs() == null) {
            spec.setTabs(new HashMap<>());
        }
        while (spec.getTabs().size() >= TAB_LIMIT) {
            spec.getTabs().entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().getTimestamp(),
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .ifPresent(oldest -> spec.getTabs().remove(oldest.getKey()));
        }
        String tabId = Base64Url.encode(SecretGenerator.getInstance().randomBytes(8));
        AuthTabSpec tab = new AuthTabSpec();
        tab.setClientId(client.getId());
        tab.setTimestamp(Time.currentTimeMillis());
        spec.getTabs().put(tabId, tab);
        refreshTimestamp();
        persist();
        AuthSessionAdapter adapter = new AuthSessionAdapter(session, this, tabId, tab);
        session.getContext().setAuthenticationSession(adapter);
        return adapter;
    }

    @Override
    public void removeAuthenticationSessionByTabId(String tabId) {
        if (spec.getTabs() == null || spec.getTabs().remove(tabId) == null) {
            return;
        }
        if (spec.getTabs().isEmpty()) {
            provider.removeRootAuthenticationSession(realm, this);
        } else {
            refreshTimestamp();
            persist();
        }
    }

    @Override
    public void restartSession(RealmModel realm) {
        spec.setTabs(null);
        refreshTimestamp();
        persist();
    }
}
