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
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode;

/**
 * Spec of a {@code KeycloakResourceServer} custom resource: the Authorization Services settings
 * of one client ("resource server"), created when authorization services are enabled on a
 * client. The clientId (== the client's store id in this store) is the store id, so there is at
 * most one CR per (realm, client). The resource server's resources, authorization scopes,
 * policies and permission tickets are separate kinds referencing this CR by
 * {@code resourceServer} = clientId.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceServerSpec implements ValueReferenceCarrier {

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

    /** clientId of the owning client, the store id; defaults to {@code metadata.name}. */
    private String clientId;

    /** Name of the realm. */
    private String realm;

    /** Whether the protection API may manage resources remotely (UMA resource registration). */
    private Boolean allowRemoteResourceManagement;

    /** Enforcing/permissive/disabled; Keycloak's default is ENFORCING. */
    private PolicyEnforcementMode policyEnforcementMode;

    /** Default decision strategy of the server's permissions; Keycloak's default is UNANIMOUS. */
    private DecisionStrategy decisionStrategy;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public Boolean getAllowRemoteResourceManagement() {
        return allowRemoteResourceManagement;
    }

    public void setAllowRemoteResourceManagement(Boolean allowRemoteResourceManagement) {
        this.allowRemoteResourceManagement = allowRemoteResourceManagement;
    }

    public PolicyEnforcementMode getPolicyEnforcementMode() {
        return policyEnforcementMode;
    }

    public void setPolicyEnforcementMode(PolicyEnforcementMode policyEnforcementMode) {
        this.policyEnforcementMode = policyEnforcementMode;
    }

    public DecisionStrategy getDecisionStrategy() {
        return decisionStrategy;
    }

    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        this.decisionStrategy = decisionStrategy;
    }
}
