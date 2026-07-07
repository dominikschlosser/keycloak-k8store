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

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Spec of a {@code KeycloakAuthzResource} custom resource: one protected resource of a resource
 * server (Authorization Services). Scoped by realm + {@code resourceServer} (the owning
 * client's clientId); the id is a generated UUID (upstream JPA convention - resource names are
 * only unique per (server, owner), so they cannot be the store id). Authorization scopes are
 * referenced by id in {@link #getScopeIds() scopeIds}; this is a plain string list, so the CRD
 * schema stays free of the representation classes' resource-scope recursion.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthzResourceSpec {

    /** Generated UUID, the store id; defaults to {@code metadata.name}. */
    private String id;

    /** Name of the realm. */
    private String realm;

    /** clientId of the owning resource server. */
    private String resourceServer;

    private String name;
    private String displayName;

    /** Resource type (e.g. {@code urn:my-app:resources:default}), shared by resource groups. */
    private String type;

    private Set<String> uris;
    private String iconUri;

    /**
     * Owner of the resource: the resource server's clientId for server-owned resources, a user
     * id for user-owned (UMA) resources.
     */
    private String owner;

    /** Whether the owner manages access via UMA permission tickets. */
    private Boolean ownerManagedAccess;

    /** Ids of the authorization scopes attached to this resource. */
    private List<String> scopeIds;

    private Map<String, List<String>> attributes;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Set<String> getUris() {
        return uris;
    }

    public void setUris(Set<String> uris) {
        this.uris = uris;
    }

    public String getIconUri() {
        return iconUri;
    }

    public void setIconUri(String iconUri) {
        this.iconUri = iconUri;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Boolean getOwnerManagedAccess() {
        return ownerManagedAccess;
    }

    public void setOwnerManagedAccess(Boolean ownerManagedAccess) {
        this.ownerManagedAccess = ownerManagedAccess;
    }

    public List<String> getScopeIds() {
        return scopeIds;
    }

    public void setScopeIds(List<String> scopeIds) {
        this.scopeIds = scopeIds;
    }

    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, List<String>> attributes) {
        this.attributes = attributes;
    }
}
