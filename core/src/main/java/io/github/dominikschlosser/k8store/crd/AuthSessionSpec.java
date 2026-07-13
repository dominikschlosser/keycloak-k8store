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
package io.github.dominikschlosser.k8store.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Spec of a {@code KeycloakAuthSession} custom resource: one root authentication session with
 * all its browser-tab sessions {@linkplain #getTabs() embedded} (map key = tab id), so an
 * in-flight login is exactly one CR regardless of how many tabs the browser opens. Volatile
 * runtime state written by Keycloak when an authentication flow starts - never author these by
 * hand. Timestamps are epoch millis; {@link #getExpiresAt() expiresAt} follows the realm's login
 * timeout settings, expired CRs are filtered on read and reaped in the background.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthSessionSpec {

    /** Root authentication session id (UUID), the store id; defaults to {@code metadata.name}. */
    private String id;

    /** Name of the realm the authentication session belongs to. */
    private String realm;

    /** Creation/refresh time, epoch millis. */
    private Long timestamp;

    /** Absolute expiration, epoch millis; absent or {@code 0} = never expires. */
    private Long expiresAt;

    /** Per-browser-tab authentication sessions, keyed by tab id. */
    private Map<String, AuthTabSpec> tabs;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Map<String, AuthTabSpec> getTabs() {
        return tabs;
    }

    public void setTabs(Map<String, AuthTabSpec> tabs) {
        this.tabs = tabs;
    }
}
