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

/**
 * Spec of a {@code KeycloakPermissionTicket} custom resource: one UMA permission request/grant —
 * a requesting party ({@code requester}) asking the {@code owner} for access to a resource
 * (optionally one scope of it). Created at runtime by UMA flows and the "my resources" account
 * area, so this kind is <em>dynamic</em>: always writable, even in read-only mode. Granting sets
 * {@code grantedTimestamp}; tickets have no expiration (they represent standing access
 * requests/grants, not the short-lived ticket JWTs).
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PermissionTicketSpec {

    /** Generated UUID, the store id; defaults to {@code metadata.name}. */
    private String id;

    /** Name of the realm. */
    private String realm;

    /** clientId of the owning resource server. */
    private String resourceServer;

    /** Owner of the resource (user id, or the resource server's clientId). */
    private String owner;

    /** User id of the requesting party. */
    private String requester;

    /** Id of the requested resource. */
    private String resourceId;

    /** Id of the requested authorization scope; null = the whole resource. */
    private String scopeId;

    /** Id of the uma-style policy attached when the ticket is granted through a policy. */
    private String policyId;

    /** Creation time, epoch millis. */
    private Long createdTimestamp;

    /** Grant time, epoch millis; null while the request is pending — non-null means granted. */
    private Long grantedTimestamp;

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

    public String getResourceServer() {
        return resourceServer;
    }

    public void setResourceServer(String resourceServer) {
        this.resourceServer = resourceServer;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRequester() {
        return requester;
    }

    public void setRequester(String requester) {
        this.requester = requester;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public Long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(Long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public Long getGrantedTimestamp() {
        return grantedTimestamp;
    }

    public void setGrantedTimestamp(Long grantedTimestamp) {
        this.grantedTimestamp = grantedTimestamp;
    }
}
