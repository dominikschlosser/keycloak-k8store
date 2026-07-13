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
import org.keycloak.representations.idm.UserConsentRepresentation;

/**
 * One client consent entry of a {@link UserSpec}: Keycloak's own consent representation
 * (client id + granted scope names + timestamps) plus the per-scope <em>parameters</em> that
 * the experimental {@code parameterized-scopes} feature attaches to granted scopes - the
 * representation has no field for them (upstream persists them in the consent-scope join
 * table), so the spec adds {@link #getGrantedScopeParameters() grantedScopeParameters}.
 *
 * <p>The map is keyed by granted scope <em>name</em> (== scope id in this store) and holds the
 * granted parameter values of that scope; only parameterized scopes appear. A parameterized
 * scope whose name is listed in {@code grantedClientScopes} without parameters here counts as
 * not granted on read (upstream parity - the JPA store persists one row per parameter and
 * none without one).
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserConsentSpec extends UserConsentRepresentation {

    private Map<String, List<String>> grantedScopeParameters;

    public Map<String, List<String>> getGrantedScopeParameters() {
        return grantedScopeParameters;
    }

    public void setGrantedScopeParameters(Map<String, List<String>> grantedScopeParameters) {
        this.grantedScopeParameters = grantedScopeParameters;
    }
}
