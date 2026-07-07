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
package com.github.dominikschlosser.k8store.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One per-browser-tab authentication session, embedded in its root session's
 * {@link AuthSessionSpec#getTabs() tabs} map — not a custom resource of its own. Carries the
 * complete flow state Keycloak tracks while a login is in progress. {@link #getTimestamp()
 * timestamp} (epoch millis) orders tabs for oldest-first eviction when a root session exceeds
 * the tab limit.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthTabSpec {

    /** Storage id of the client the tab authenticates against. */
    private String clientId;

    /** Creation time, epoch millis; eviction order for the per-root tab limit. */
    private Long timestamp;

    private String redirectUri;
    private String action;
    private String protocol;

    /** Id of the (possibly partially) authenticated user, if any. */
    private String authUserId;

    /** Flow execution id to {@code CommonClientSessionModel.ExecutionStatus} name. */
    private Map<String, String> executionStatus;

    private List<String> requiredActions;

    private Map<String, String> userSessionNotes;

    private Map<String, String> authNotes;

    private Map<String, String> clientNotes;

    private List<String> clientScopes;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getAuthUserId() {
        return authUserId;
    }

    public void setAuthUserId(String authUserId) {
        this.authUserId = authUserId;
    }

    public Map<String, String> getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(Map<String, String> executionStatus) {
        this.executionStatus = executionStatus;
    }

    public List<String> getRequiredActions() {
        return requiredActions;
    }

    public void setRequiredActions(List<String> requiredActions) {
        this.requiredActions = requiredActions;
    }

    public Map<String, String> getUserSessionNotes() {
        return userSessionNotes;
    }

    public void setUserSessionNotes(Map<String, String> userSessionNotes) {
        this.userSessionNotes = userSessionNotes;
    }

    public Map<String, String> getAuthNotes() {
        return authNotes;
    }

    public void setAuthNotes(Map<String, String> authNotes) {
        this.authNotes = authNotes;
    }

    public Map<String, String> getClientNotes() {
        return clientNotes;
    }

    public void setClientNotes(Map<String, String> clientNotes) {
        this.clientNotes = clientNotes;
    }

    public List<String> getClientScopes() {
        return clientScopes;
    }

    public void setClientScopes(List<String> clientScopes) {
        this.clientScopes = clientScopes;
    }
}
