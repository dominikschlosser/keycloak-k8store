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
import java.util.List;

/**
 * Spec of a {@code KeycloakAuthzScope} custom resource: one authorization scope of a resource
 * server (Authorization Services - not to be confused with OAuth client scopes, which are the
 * {@code KeycloakClientScope} kind). Scoped by realm + {@code resourceServer} (the owning
 * client's clientId); the id is a generated UUID, names are unique per resource server.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthzScopeSpec implements ValueReferenceCarrier {

    /**
     * Secret, ConfigMap and literal references this resource may pull into its {@code ${...}}
     * placeholders. Resolved on read only. See {@link ValueReferenceCarrier}.
     */
    private List<ValueReference> valuesFrom;

    @Override
    public List<ValueReference> getValuesFrom() {
        return valuesFrom;
    }

    @Override
    public void setValuesFrom(List<ValueReference> valuesFrom) {
        this.valuesFrom = valuesFrom;
    }

    /** Generated UUID, the store id; defaults to {@code metadata.name}. */
    private String id;

    /** Name of the realm. */
    private String realm;

    /** clientId of the owning resource server. */
    private String resourceServer;

    private String name;
    private String displayName;
    private String iconUri;

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

    public String getIconUri() {
        return iconUri;
    }

    public void setIconUri(String iconUri) {
        this.iconUri = iconUri;
    }
}
