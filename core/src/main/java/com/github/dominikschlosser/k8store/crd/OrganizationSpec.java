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
import java.util.Set;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;

/**
 * Spec of a {@code KeycloakOrganization} custom resource: Keycloak's organization
 * representation plus the {@link #getRealm() realm} and the {@link #getGroupId() backing group}
 * reference. It carries the organization <em>definition</em> — name, alias, enabled flag,
 * description, redirect URL, email domains and attributes.
 *
 * <p>What deliberately does <em>not</em> live here (the CR would only duplicate other storage):
 *
 * <ul>
 *   <li>{@code members} — membership is group membership of the backing group and lives on the
 *       user side (database rows or {@code KeycloakUser} CRs), so member joins keep working
 *       when organization CRs are read-only;
 *   <li>{@code groups} — the backing group and organization-scoped subgroups are {@code
 *       KeycloakGroup} CRs ({@code spec.type: organization}, {@code spec.organizationId});
 *   <li>{@code identityProviders} — the linkage is the {@code organizationId} field of the
 *       identity provider entries in the realm CR, exactly where Keycloak's model keeps it.
 * </ul>
 *
 * All three are excluded from the CRD schema but still deserialize, so a hand-authored spec
 * carrying them is reported ({@link #ignoredEmbeddedCollections()}) instead of silently pruned.
 *
 * <p>Unlike upstream's JPA store (which keeps organization attributes on the backing group),
 * attributes are stored in this spec — the representation carries them and that is where a
 * GitOps author expects them.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL, content = JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationSpec extends OrganizationRepresentation {

    /**
     * Name of the realm this organization belongs to. Required in hand-authored CRs
     * (alternatively via the realm label); always set on CRs written by Keycloak.
     */
    private String realm;

    /**
     * Id of the organization's backing group (a {@code KeycloakGroup} CR with {@code type:
     * organization} whose name is this organization's id). Optional in hand-authored CRs: when
     * absent, the backing group is resolved by the convention group id = organization id.
     */
    private String groupId;

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * The representation only exposes {@code addDomain}/{@code removeDomain}; without a setter
     * Jackson would treat the null-returning getter as a setterless property and fail to
     * deserialize hand-authored {@code domains}.
     */
    public void setDomains(Set<OrganizationDomainRepresentation> domains) {
        if (getDomains() != null) {
            List.copyOf(getDomains()).forEach(this::removeDomain);
        }
        if (domains != null) {
            domains.forEach(this::addDomain);
        }
    }

    // Excluded from the CRD schema and from serialization, but still deserializable (getter
    // ignored, setter re-enabled) so that a spec authored with embedded collections can be
    // detected and reported instead of silently dropped. See ignoredEmbeddedCollections().

    @JsonIgnore
    @Override
    public List<MemberRepresentation> getMembers() {
        return super.getMembers();
    }

    @JsonProperty("members")
    @Override
    public void setMembers(List<MemberRepresentation> members) {
        super.setMembers(members);
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
    public List<IdentityProviderRepresentation> getIdentityProviders() {
        return super.getIdentityProviders();
    }

    @JsonProperty("identityProviders")
    @Override
    public void setIdentityProviders(List<IdentityProviderRepresentation> identityProviders) {
        super.setIdentityProviders(identityProviders);
    }

    /**
     * Names of the embedded collections present in this spec that this store ignores because
     * their content lives elsewhere (members on the user side, groups as group CRs, identity
     * providers in the realm CR); used for the one-time warning on hand-authored CRs.
     */
    public List<String> ignoredEmbeddedCollections() {
        List<String> present = new ArrayList<>();
        if (getMembers() != null && !getMembers().isEmpty()) {
            present.add("members");
        }
        if (getGroups() != null && !getGroups().isEmpty()) {
            present.add("groups");
        }
        if (getIdentityProviders() != null && !getIdentityProviders().isEmpty()) {
            present.add("identityProviders");
        }
        return present;
    }
}
