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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.keycloak.representations.idm.SocialLinkRepresentation;
import org.keycloak.representations.idm.UserConsentRepresentation;
import org.keycloak.representations.idm.UserProfileMetadata;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.oid4vc.IssuedVerifiableCredentialRepresentation;
import org.keycloak.representations.idm.oid4vc.UserVerifiableCredentialRepresentation;

/**
 * Spec of a {@code KeycloakUser} custom resource: Keycloak's own user representation plus the
 * {@link #getRealm() realm} that scopes it, so the CR body reads mostly like standard Keycloak
 * user JSON.
 *
 * <p><strong>Security:</strong> user CRs carry password hashes and OTP secrets in
 * {@code spec.credentials} ({@code secretData}/{@code credentialData} JSON, hashed through
 * Keycloak's password-hash providers - never plaintext) and, when identity-broker token storage
 * is enabled, broker tokens in {@link #getFederatedIdentityTokens() federatedIdentityTokens}.
 * RBAC read access to {@code keycloakusers} resources must be locked down accordingly.
 *
 * <p>Identity and conventions:
 *
 * <ul>
 *   <li>The store id is {@code spec.id}, fixed at creation to the <em>lowercased username</em>
 *       (human-readable CR names, like the other kinds) and immutable afterwards - renaming a
 *       user keeps its id, so sessions, tokens ({@code sub}) and login-failure CRs stay valid.
 *       Hand-authored CRs may omit it ({@code metadata.name} is the fallback).
 *   <li>{@code username} and {@code email} are stored lowercased (JPA parity - upstream
 *       normalizes both on write), which makes every lookup case-insensitive by construction.
 *       The realm attribute {@code keycloak.username-search.case-sensitive} is not honored.
 *   <li>{@code realmRoles} holds realm role <em>names</em> (== realm role ids in this store);
 *       {@code clientRoles} is keyed by the owning client's id (== clientId) with role names -
 *       the same by-name convention as composites and scope mappings.
 *   <li>{@code groups} holds group <em>ids</em> (immutable at creation), <em>not</em> the group
 *       paths of Keycloak's import representations - paths would break on parent renames.
 *   <li>{@code credentials} order <em>is</em> the credential priority order; the entries carry
 *       only {@code id/type/userLabel/createdDate/secretData/credentialData}.
 *   <li>{@code serviceAccountClientId} links a service-account user to its client (== clientId).
 * </ul>
 *
 * <p>Not part of the CRD schema: {@code self}, {@code origin} and {@code access} (per-request
 * admin metadata), {@code userProfileMetadata} (computed), {@code disableableCredentialTypes}
 * (computed from the credential providers), the deprecated {@code totp}/{@code socialLinks}/
 * {@code applicationRoles} import fields, and the OID4VC {@code verifiableCredentials}/
 * {@code issuedVerifiableCredentials} (the feature is unsupported by this store).
 *
 * <p>Serialization rules: {@code null} properties and {@code null} map values are dropped - a
 * real API server rejects explicit nulls in {@code map<string,string>} schema fields with 422.
 * Unknown properties are ignored on read.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserSpec extends UserRepresentation {

    /**
     * Name of the realm this user belongs to. Required in hand-authored CRs (alternatively via
     * the realm label); always set on CRs written by Keycloak.
     */
    private String realm;

    /**
     * Identity-broker tokens by identity-provider alias, stored only when the provider's
     * "store tokens" option is on. Sensitive - see the class-level security note.
     */
    private Map<String, String> federatedIdentityTokens;

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    /**
     * The consent entries - the standard {@code clientConsents} JSON shape, but typed as
     * {@link UserConsentSpec} so per-scope parameters of the (experimental)
     * parameterized-scopes feature persist alongside the granted scope names. Replaces the
     * inherited representation-typed property under the same JSON name.
     */
    private List<UserConsentSpec> consents;

    public Map<String, String> getFederatedIdentityTokens() {
        return federatedIdentityTokens;
    }

    public void setFederatedIdentityTokens(Map<String, String> federatedIdentityTokens) {
        this.federatedIdentityTokens = federatedIdentityTokens;
    }

    @JsonProperty("clientConsents")
    public List<UserConsentSpec> getConsents() {
        return consents;
    }

    @JsonProperty("clientConsents")
    public void setConsents(List<UserConsentSpec> consents) {
        this.consents = consents;
    }

    /** Hidden (see {@link #getConsents()} - same JSON property, parameter-aware type). */
    @JsonIgnore
    @Override
    public List<UserConsentRepresentation> getClientConsents() {
        return super.getClientConsents();
    }

    /** Hidden (see {@link #setConsents(List)}). */
    @JsonIgnore
    @Override
    public void setClientConsents(List<UserConsentRepresentation> clientConsents) {
        super.setClientConsents(clientConsents);
    }

    /** Excluded from the CRD schema: a REST resource link is per-request metadata. */
    @JsonIgnore
    @Override
    public String getSelf() {
        return super.getSelf();
    }

    /** Excluded from the CRD schema: import bookkeeping of the admin API. */
    @JsonIgnore
    @Override
    public String getOrigin() {
        return super.getOrigin();
    }

    /** Excluded from the CRD schema: per-caller admin permissions are runtime information. */
    @JsonIgnore
    @Override
    public Map<String, Boolean> getAccess() {
        return super.getAccess();
    }

    /** Excluded from the CRD schema: user-profile metadata is computed per request. */
    @JsonIgnore
    @Override
    public UserProfileMetadata getUserProfileMetadata() {
        return super.getUserProfileMetadata();
    }

    /**
     * Excluded from the CRD schema: whether credential types can be disabled is computed from
     * the registered credential providers, never stored.
     */
    @JsonIgnore
    @Override
    public Set<String> getDisableableCredentialTypes() {
        return super.getDisableableCredentialTypes();
    }

    /** Excluded from the CRD schema: deprecated import-only flag. */
    @Deprecated
    @JsonIgnore
    @Override
    public Boolean isTotp() {
        return super.isTotp();
    }

    /** Excluded from the CRD schema: deprecated import-only shape (see {@code clientRoles}). */
    @JsonIgnore
    @Override
    public Map<String, List<String>> getApplicationRoles() {
        return super.getApplicationRoles();
    }

    /** Excluded from the CRD schema: deprecated import-only shape (see {@code federatedIdentities}). */
    @JsonIgnore
    @Override
    public List<SocialLinkRepresentation> getSocialLinks() {
        return super.getSocialLinks();
    }

    /** Excluded from the CRD schema: the OID4VC feature is unsupported by this store. */
    @JsonIgnore
    @Override
    public List<UserVerifiableCredentialRepresentation> getVerifiableCredentials() {
        return super.getVerifiableCredentials();
    }

    /** Excluded from the CRD schema: the OID4VC feature is unsupported by this store. */
    @JsonIgnore
    @Override
    public List<IssuedVerifiableCredentialRepresentation> getIssuedVerifiableCredentials() {
        return super.getIssuedVerifiableCredentials();
    }
}
