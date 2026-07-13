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
package io.github.dominikschlosser.k8store.organization;

import io.github.dominikschlosser.k8store.crd.OrganizationInvitationSpec;
import java.util.Objects;
import org.keycloak.models.OrganizationInvitationModel;

/**
 * {@link OrganizationInvitationModel} over an {@link OrganizationInvitationSpec}. Every setter
 * persists the spec - the invitation flows mutate the model after creation (invite link).
 */
public class OrganizationInvitationAdapter implements OrganizationInvitationModel {

    private final OrganizationInvitationSpec spec;

    OrganizationInvitationAdapter(OrganizationInvitationSpec spec) {
        Objects.requireNonNull(spec, "spec");
        this.spec = spec;
    }

    private void persist() {
        OrganizationInvitationCrStore.save(spec);
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public String getOrganizationId() {
        return spec.getOrganizationId();
    }

    @Override
    public String getEmail() {
        return spec.getEmail();
    }

    @Override
    public void setEmail(String email) {
        spec.setEmail(email);
        persist();
    }

    @Override
    public String getFirstName() {
        return spec.getFirstName();
    }

    @Override
    public void setFirstName(String firstName) {
        spec.setFirstName(firstName);
        persist();
    }

    @Override
    public String getLastName() {
        return spec.getLastName();
    }

    @Override
    public void setLastName(String lastName) {
        spec.setLastName(lastName);
        persist();
    }

    @Override
    public int getCreatedAt() {
        return spec.getCreatedAt() == null ? 0 : spec.getCreatedAt();
    }

    @Override
    public int getExpiresAt() {
        return spec.getExpiresAt() == null ? 0 : spec.getExpiresAt();
    }

    @Override
    public void setExpiresAt(int expiresAt) {
        spec.setExpiresAt(expiresAt);
        persist();
    }

    @Override
    public String getInviteLink() {
        return spec.getInviteLink();
    }

    @Override
    public void setInviteLink(String inviteLink) {
        spec.setInviteLink(inviteLink);
        persist();
    }

    @Override
    public InvitationStatus getStatus() {
        return isExpired() ? InvitationStatus.EXPIRED : InvitationStatus.PENDING;
    }
}
