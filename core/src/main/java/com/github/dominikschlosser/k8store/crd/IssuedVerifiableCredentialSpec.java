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
 * Spec of a {@code KeycloakIssuedVerifiableCredential} custom resource: one OID4VC issuance
 * event - a concrete credential issued to a client for a user's
 * {@link UserVerifiableCredentialSpec verifiable credential} (CR shape of upstream's
 * {@code ISSUED_VERIFIABLE_CREDENTIAL} table). Written by Keycloak's OID4VC issuance flows at
 * runtime - never author these by hand. Only exists (and is only watched) when the {@code user}
 * area and the {@code oid4vc-vci} feature are both enabled.
 *
 * <p>Timestamps are epoch millis; {@link #getExpiresAt() expiresAt} ({@code null} = never) is
 * wired into the store's expiry handling - reads filter expired entries and the background
 * reaper deletes their CRs (this store's equivalent of upstream's scheduled cleanup task).
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IssuedVerifiableCredentialSpec {

    /** Generated store id. */
    private String id;

    /** Name of the realm the owning user belongs to. */
    private String realm;

    /** Store id of the owning user. */
    private String userId;

    /** Id of the {@link UserVerifiableCredentialSpec} this issuance belongs to. */
    private String verifiableCredentialId;

    /** Id of the client the credential was issued to. */
    private String clientId;

    /** Revision of the verifiable credential at issuance time. */
    private String revision;

    private Long issuedAt;

    /** Absolute expiration, epoch millis; {@code null} = never expires. */
    private Long expiresAt;

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getVerifiableCredentialId() {
        return verifiableCredentialId;
    }

    public void setVerifiableCredentialId(String verifiableCredentialId) {
        this.verifiableCredentialId = verifiableCredentialId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public Long getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Long issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
