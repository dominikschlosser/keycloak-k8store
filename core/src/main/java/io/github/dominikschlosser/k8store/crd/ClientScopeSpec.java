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
import java.util.Map;
import org.keycloak.representations.idm.ClientScopeRepresentation;

/**
 * Spec of a {@code KeycloakClientScope} custom resource: Keycloak's own client-scope
 * representation plus the {@link #getRealm() realm} that scopes it, so the CR body reads exactly
 * like standard Keycloak client-scope JSON.
 *
 * <p>Identity: the store id <em>is</em> the scope name (defaulting to {@code metadata.name} when
 * omitted in hand-authored CRs); {@code spec.id} is kept equal to it. Role scope mappings have
 * no field in the representation (exports keep them on the realm), so the spec adds the explicit
 * {@link ScopeMappingCarrier} fields.
 *
 * <p>Serialization rules: {@code null} properties and {@code null} map values are dropped - a
 * real API server rejects explicit nulls in {@code map<string,string>} schema fields with 422.
 * Unknown properties are ignored on read.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientScopeSpec extends ClientScopeRepresentation implements ProtocolMapperCarrier, ScopeMappingCarrier {

    /**
     * Name of the realm this client scope belongs to. Required in hand-authored CRs
     * (alternatively via the realm label); always set on CRs written by Keycloak.
     */
    private String realm;

    /** Realm role names this scope maps into tokens (see {@link ScopeMappingCarrier}). */
    private List<String> realmScopeMappings;

    /** Client role scope mappings, keyed by the owning client's id (= clientId). */
    private Map<String, List<String>> clientScopeMappings;

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    @Override
    public List<String> getRealmScopeMappings() {
        return realmScopeMappings;
    }

    @Override
    public void setRealmScopeMappings(List<String> realmScopeMappings) {
        this.realmScopeMappings = realmScopeMappings;
    }

    @Override
    public Map<String, List<String>> getClientScopeMappings() {
        return clientScopeMappings;
    }

    @Override
    public void setClientScopeMappings(Map<String, List<String>> clientScopeMappings) {
        this.clientScopeMappings = clientScopeMappings;
    }
}
