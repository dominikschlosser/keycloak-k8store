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
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * Spec of a {@code KeycloakRole} custom resource: Keycloak's own role representation plus the
 * {@link #getRealm() realm} that scopes it, so the CR body reads exactly like standard Keycloak
 * role JSON.
 *
 * <p>Identity: {@code spec.id} is the store id and defaults to {@code metadata.name} when
 * omitted. Keycloak writes human-readable ids - the role name for realm roles,
 * {@code <clientId>:<name>} for client roles. Client roles carry {@code clientRole: true} and
 * {@code containerId} referencing the owning client; composites are stored the standard
 * representation way ({@code composites.realm} role names, {@code composites.client} keyed by
 * client).
 *
 * <p>Serialization rules: {@code null} properties and {@code null} map values are dropped - a
 * real API server rejects explicit nulls in {@code map<string,string>} schema fields with 422.
 * Unknown properties are ignored on read.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoleSpec extends RoleRepresentation {

    /**
     * Name of the realm this role belongs to. Required in hand-authored CRs (alternatively via
     * the realm label); always set on CRs written by Keycloak.
     */
    private String realm;

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }
}
