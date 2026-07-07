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
 * Spec of a {@code KeycloakOrganizationInvitation} custom resource: one pending (or expired)
 * invitation of an email address into an organization. No Keycloak representation matches this
 * shape (the admin representation is a REST-only view), so the spec is an original POJO
 * mirroring the upstream entity: id, organization id, invitee email/first/last name, creation
 * and expiry timestamps (epoch <em>seconds</em>, upstream convention for invitations) and the
 * invitation link sent by email.
 *
 * <p>Invitations are <strong>runtime data</strong> written by the invitation flows - the kind
 * stays writable in read-only mode, like permission tickets. Expired invitations are kept (they
 * remain listable with the {@code EXPIRED} status filter, upstream parity) until removed by
 * acceptance, revocation, organization removal or realm removal.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationInvitationSpec {

    /** Name of the realm the organization belongs to. */
    private String realm;

    /** Generated invitation id (store id; defaults to {@code metadata.name}). */
    private String id;

    /** Id of the organization the invitee is invited into. */
    private String organizationId;

    private String email;
    private String firstName;
    private String lastName;

    /** Creation time, epoch seconds. */
    private Integer createdAt;

    /** Expiry, epoch seconds (creation + the realm's admin action token lifespan). */
    private Integer expiresAt;

    /** The invitation link mailed to the invitee. */
    private String inviteLink;

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public Integer getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Integer createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Integer expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getInviteLink() {
        return inviteLink;
    }

    public void setInviteLink(String inviteLink) {
        this.inviteLink = inviteLink;
    }
}
