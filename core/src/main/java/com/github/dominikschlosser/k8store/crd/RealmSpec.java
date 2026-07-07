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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.ApplicationRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ClientTemplateRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.OAuthClientRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RolesRepresentation;
import org.keycloak.representations.idm.ScopeMappingRepresentation;
import org.keycloak.representations.idm.UserFederationMapperRepresentation;
import org.keycloak.representations.idm.UserFederationProviderRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Spec of a {@code KeycloakRealm} custom resource: Keycloak's own realm representation, so the
 * CR body reads exactly like standard Keycloak realm JSON (the format of realm exports and the
 * Admin REST API).
 *
 * <p>Identity: {@code spec.realm} — the realm name, which doubles as the realm id in this store —
 * defaults to {@code metadata.name} when omitted from a hand-authored CR; {@code spec.id} is kept
 * equal to it.
 *
 * <p>Served from this spec: identity providers and their mappers, authentication flows (with
 * their executions in the standard nested export shape), authenticator configs, required
 * actions, components ({@code providerType -> [component]}, nested via {@code subComponents}),
 * default role/groups/client-scope references, localization texts and every scalar realm
 * setting. Flow bindings ({@code browserFlow}, ...) and authenticator-config references inside
 * executions use <em>aliases</em>, exactly like realm exports.
 *
 * <p><b>Not served — per-kind CRs are the storage:</b> the embedded {@code users},
 * {@code federatedUsers}, {@code clients}, {@code clientScopes}, {@code roles}, {@code groups}
 * and {@code clientScopeMappings}/{@code scopeMappings} collections of a realm export are
 * excluded from the CRD schema and their content is ignored (with a warning) if it reaches the
 * store. Author {@code KeycloakClient}/{@code KeycloakRole}/... CRs instead. Import/export-only
 * and unsupported fields (deprecated {@code applications}/{@code oauthClients} shapes, realm
 * key-pair import fields, {@code organizations}, client profiles/policies JSON — the latter are
 * served from realm attributes at runtime) are excluded as well.
 *
 * <p>Serialization rules: {@code null} properties and {@code null} map values are dropped — a
 * real API server rejects explicit nulls in {@code map<string,string>} schema fields with 422.
 * Unknown properties are ignored on read so CRs written by a newer schema generation do not
 * break older nodes during a rolling upgrade.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
// clientProfiles/clientPolicies are raw-JSON fields bound at the field level in the superclass
// (no overridable accessor), hence excluded by name here
@JsonIgnoreProperties(value = {"clientProfiles", "clientPolicies"}, ignoreUnknown = true)
public class RealmSpec extends RealmRepresentation {

    /**
     * Id of this realm's admin client in the {@code master} realm ({@code <realm>-realm}).
     * Realm exports do not carry it (it is re-created at import), but this store serves realms
     * without an import step, so the reference is persisted explicitly.
     */
    private String masterAdminClient;

    /** Client-initial-access tokens; no representation exists for them (see the spec class). */
    private List<ClientInitialAccessSpec> clientInitialAccesses;

    public String getMasterAdminClient() {
        return masterAdminClient;
    }

    public void setMasterAdminClient(String masterAdminClient) {
        this.masterAdminClient = masterAdminClient;
    }

    public List<ClientInitialAccessSpec> getClientInitialAccesses() {
        return clientInitialAccesses;
    }

    public void setClientInitialAccesses(List<ClientInitialAccessSpec> clientInitialAccesses) {
        this.clientInitialAccesses = clientInitialAccesses;
    }

    // ------------------------------------------------------------------ embedded per-kind collections
    // Excluded from the CRD schema and from serialization, but still deserializable (getter
    // ignored, setter re-enabled) so that a spec authored with embedded collections can be
    // detected and reported instead of silently dropped. See ignoredEmbeddedCollections().

    @JsonIgnore
    @Override
    public List<UserRepresentation> getUsers() {
        return super.getUsers();
    }

    @JsonProperty("users")
    @Override
    public void setUsers(List<UserRepresentation> users) {
        super.setUsers(users);
    }

    @JsonIgnore
    @Override
    public List<UserRepresentation> getFederatedUsers() {
        return super.getFederatedUsers();
    }

    @JsonProperty("federatedUsers")
    @Override
    public void setFederatedUsers(List<UserRepresentation> users) {
        super.setFederatedUsers(users);
    }

    @JsonIgnore
    @Override
    public List<ClientRepresentation> getClients() {
        return super.getClients();
    }

    @JsonProperty("clients")
    @Override
    public void setClients(List<ClientRepresentation> clients) {
        super.setClients(clients);
    }

    @JsonIgnore
    @Override
    public List<ClientScopeRepresentation> getClientScopes() {
        return super.getClientScopes();
    }

    @JsonProperty("clientScopes")
    @Override
    public void setClientScopes(List<ClientScopeRepresentation> clientScopes) {
        super.setClientScopes(clientScopes);
    }

    @JsonIgnore
    @Override
    public RolesRepresentation getRoles() {
        return super.getRoles();
    }

    @JsonProperty("roles")
    @Override
    public void setRoles(RolesRepresentation roles) {
        super.setRoles(roles);
    }

    @JsonIgnore
    @Override
    public List<GroupRepresentation> getGroups() {
        return super.getGroups();
    }

    @JsonProperty("groups")
    @Override
    public void setGroups(List<GroupRepresentation> groups) {
        super.setGroups(groups);
    }

    @JsonIgnore
    @Override
    public Map<String, List<ScopeMappingRepresentation>> getClientScopeMappings() {
        return super.getClientScopeMappings();
    }

    @JsonProperty("clientScopeMappings")
    @Override
    public void setClientScopeMappings(Map<String, List<ScopeMappingRepresentation>> mappings) {
        super.setClientScopeMappings(mappings);
    }

    /**
     * Embedded per-kind collections present on this spec, by field name. Per-kind CRs are the
     * storage — content listed here is not served and callers should warn about it.
     */
    @JsonIgnore
    public List<String> ignoredEmbeddedCollections() {
        List<String> present = new ArrayList<>();
        if (getUsers() != null && !getUsers().isEmpty()) {
            present.add("users");
        }
        if (getFederatedUsers() != null && !getFederatedUsers().isEmpty()) {
            present.add("federatedUsers");
        }
        if (getClients() != null && !getClients().isEmpty()) {
            present.add("clients");
        }
        if (getClientScopes() != null && !getClientScopes().isEmpty()) {
            present.add("clientScopes");
        }
        if (getRoles() != null) {
            present.add("roles");
        }
        if (getGroups() != null && !getGroups().isEmpty()) {
            present.add("groups");
        }
        if (getClientScopeMappings() != null && !getClientScopeMappings().isEmpty()) {
            present.add("clientScopeMappings");
        }
        return present;
    }

    // ------------------------------------------------------------------ excluded entirely
    // Import/export-only or unsupported fields: not in the schema, not read.

    /** Excluded: realm-level scope mappings are stored on the client/scope CRs. */
    @JsonIgnore
    @Override
    public List<ScopeMappingRepresentation> getScopeMappings() {
        return super.getScopeMappings();
    }

    /** Excluded: deprecated pre-OIDC application shape. */
    @JsonIgnore
    @Override
    public List<ApplicationRepresentation> getApplications() {
        return super.getApplications();
    }

    /** Excluded: deprecated pre-OIDC client shape. */
    @JsonIgnore
    @Override
    public List<OAuthClientRepresentation> getOauthClients() {
        return super.getOauthClients();
    }

    /** Excluded: deprecated client templates (superseded by client scopes). */
    @JsonIgnore
    @Override
    public List<ClientTemplateRepresentation> getClientTemplates() {
        return super.getClientTemplates();
    }

    /** Excluded: deprecated application scope mappings. */
    @JsonIgnore
    @Override
    public Map<String, List<ScopeMappingRepresentation>> getApplicationScopeMappings() {
        return super.getApplicationScopeMappings();
    }

    /** Excluded: deprecated social provider shape (superseded by identity providers). */
    @JsonIgnore
    @Override
    public Map<String, String> getSocialProviders() {
        return super.getSocialProviders();
    }

    /** Excluded: the organizations feature is not supported by this store. */
    @JsonIgnore
    @Override
    public List<OrganizationRepresentation> getOrganizations() {
        return super.getOrganizations();
    }

    /** Excluded: realm-level protocol mappers are a deprecated import shape. */
    @JsonIgnore
    @Override
    public List<ProtocolMapperRepresentation> getProtocolMappers() {
        return super.getProtocolMappers();
    }

    /** Excluded: deprecated user federation shape (superseded by user-storage components). */
    @JsonIgnore
    @Override
    public List<UserFederationProviderRepresentation> getUserFederationProviders() {
        return super.getUserFederationProviders();
    }

    /** Excluded: deprecated user federation shape (superseded by user-storage components). */
    @JsonIgnore
    @Override
    public List<UserFederationMapperRepresentation> getUserFederationMappers() {
        return super.getUserFederationMappers();
    }

    /**
     * Excluded: the reference is stored in the {@code adminPermissionsClientId} realm attribute;
     * embedding a full client representation would also pull the recursive authorization
     * settings graph into the schema.
     */
    @JsonIgnore
    @Override
    public ClientRepresentation getAdminPermissionsClient() {
        return super.getAdminPermissionsClient();
    }

    /** Excluded: legacy key-pair import field — realm keys are key-provider components. */
    @JsonIgnore
    @Override
    public String getPrivateKey() {
        return super.getPrivateKey();
    }

    /** Excluded: legacy key-pair import field — realm keys are key-provider components. */
    @JsonIgnore
    @Override
    public String getPublicKey() {
        return super.getPublicKey();
    }

    /** Excluded: legacy key-pair import field — realm keys are key-provider components. */
    @JsonIgnore
    @Override
    public String getCertificate() {
        return super.getCertificate();
    }

    /** Excluded: legacy import field. */
    @JsonIgnore
    @Override
    public String getCodeSecret() {
        return super.getCodeSecret();
    }
}
