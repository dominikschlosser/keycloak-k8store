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
 * Spec of a {@code KeycloakUserSession} custom resource: one authenticated user session (online
 * or offline), with its client sessions {@linkplain #getClientSessions() embedded} in a map keyed
 * by the client's storage id. These CRs are volatile runtime state written by Keycloak on every
 * login/refresh - never author them by hand.
 *
 * <p>Original shape (Keycloak has no session representation class): all timestamps are epoch
 * milliseconds. {@link #getExpiresAt() expiresAt} is maintained by the provider from the realm's
 * and clients' session timeout settings; the storage backend filters expired sessions on read and
 * a background reaper deletes their CRs. Offline sessions are separate CRs ({@code offline:
 * true}) linked to their online counterpart through the {@code correspondingSessionId} note on
 * both sides.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserSessionSpec {

    /** Session id (UUID), the store id; defaults to {@code metadata.name}. */
    private String id;

    /** Name of the realm the session belongs to. */
    private String realm;

    private String userId;
    private String loginUsername;
    private String ipAddress;
    private String authMethod;
    private Boolean rememberMe;
    private String brokerSessionId;
    private String brokerUserId;

    /** Login time, epoch millis. */
    private Long started;

    /** Last refresh time, epoch millis. */
    private Long lastSessionRefresh;

    /** Offline sessions live in their own CR; absent means online. */
    private Boolean offline;

    /** {@code org.keycloak.models.UserSessionModel.State} name. */
    private String state;

    private Map<String, String> notes;

    /** Client sessions embedded by client storage id. */
    private Map<String, ClientSessionSpec> clientSessions;

    /** Absolute expiration, epoch millis; absent or {@code 0} = never expires. */
    private Long expiresAt;

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLoginUsername() {
        return loginUsername;
    }

    public void setLoginUsername(String loginUsername) {
        this.loginUsername = loginUsername;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(String authMethod) {
        this.authMethod = authMethod;
    }

    public Boolean getRememberMe() {
        return rememberMe;
    }

    public void setRememberMe(Boolean rememberMe) {
        this.rememberMe = rememberMe;
    }

    public String getBrokerSessionId() {
        return brokerSessionId;
    }

    public void setBrokerSessionId(String brokerSessionId) {
        this.brokerSessionId = brokerSessionId;
    }

    public String getBrokerUserId() {
        return brokerUserId;
    }

    public void setBrokerUserId(String brokerUserId) {
        this.brokerUserId = brokerUserId;
    }

    public Long getStarted() {
        return started;
    }

    public void setStarted(Long started) {
        this.started = started;
    }

    public Long getLastSessionRefresh() {
        return lastSessionRefresh;
    }

    public void setLastSessionRefresh(Long lastSessionRefresh) {
        this.lastSessionRefresh = lastSessionRefresh;
    }

    public Boolean getOffline() {
        return offline;
    }

    public void setOffline(Boolean offline) {
        this.offline = offline;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Map<String, String> getNotes() {
        return notes;
    }

    public void setNotes(Map<String, String> notes) {
        this.notes = notes;
    }

    public Map<String, ClientSessionSpec> getClientSessions() {
        return clientSessions;
    }

    public void setClientSessions(Map<String, ClientSessionSpec> clientSessions) {
        this.clientSessions = clientSessions;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
