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
import java.util.List;
import java.util.Map;

/**
 * Spec of a {@code KeycloakUserVerifiableCredential} custom resource: one OID4VC verifiable
 * credential of a user (the user-side registration of a credential scope, CR shape of upstream's
 * {@code USER_VERIFIABLE_CREDENTIAL} table). Written by Keycloak's OID4VC issuance and admin
 * flows at runtime - never author these by hand. Only exists (and is only watched) when the
 * {@code user} area and the {@code oid4vc-vci} feature are both enabled.
 *
 * <p>Timestamps are epoch millis. {@link #getUserAttributes() userAttributes} is the snapshot of
 * the user's readable profile attributes taken at creation/update time (upstream stores the same
 * snapshot as a JSON column) - it may contain personal data, the same RBAC caveat as
 * {@code keycloakusers} applies.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserVerifiableCredentialSpec {

    /** Generated store id (upstream convention: verifiable-credential ids are opaque). */
    private String id;

    /** Name of the realm the owning user belongs to. */
    private String realm;

    /** Store id of the owning user. */
    private String userId;

    /** Id of the credential scope (a client scope) this credential is bound to. */
    private String clientScopeId;

    /** Opaque revision, regenerated on every update (optimistic-staleness marker for tokens). */
    private String revision;

    private Long createdDate;

    private Long updatedDate;

    /** Snapshot of the user's readable profile attributes at creation/update time. */
    private Map<String, List<String>> userAttributes;

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

    public String getClientScopeId() {
        return clientScopeId;
    }

    public void setClientScopeId(String clientScopeId) {
        this.clientScopeId = clientScopeId;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public Long getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Long createdDate) {
        this.createdDate = createdDate;
    }

    public Long getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Long updatedDate) {
        this.updatedDate = updatedDate;
    }

    public Map<String, List<String>> getUserAttributes() {
        return userAttributes;
    }

    public void setUserAttributes(Map<String, List<String>> userAttributes) {
        this.userAttributes = userAttributes;
    }
}
