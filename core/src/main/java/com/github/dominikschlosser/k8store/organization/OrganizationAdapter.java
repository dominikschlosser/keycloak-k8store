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
package com.github.dominikschlosser.k8store.organization;

import com.github.dominikschlosser.k8store.crd.OrganizationSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelValidationException;
import org.keycloak.models.OrganizationDomainModel;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.utils.Organizations;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.utils.StringUtil;

/**
 * {@link OrganizationModel} over an {@link OrganizationSpec}. The adapter owns a defensive copy
 * of the CR spec; every mutation is persisted explicitly and as a whole. Semantics mirror the
 * upstream JPA adapter: the alias is immutable once set, the enabled flag combines the realm's
 * organizations switch with the organization's own flag, domain updates validate the domain
 * format and cross-organization uniqueness and strip the {@code kc.org.domain} config key from
 * identity providers whose domain was removed. Attributes live in the spec (not on the backing
 * group as in JPA — the CR is the authored artifact).
 */
public class OrganizationAdapter implements OrganizationModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final OrganizationSpec spec;
    private final OrganizationCrProvider provider;

    OrganizationAdapter(KeycloakSession session, RealmModel realm, OrganizationSpec spec,
            OrganizationCrProvider provider) {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(spec, "spec");
        this.session = session;
        this.realm = realm;
        this.spec = spec;
        this.provider = provider;
    }

    OrganizationSpec spec() {
        return spec;
    }

    RealmModel realm() {
        return realm;
    }

    /** Id of the organization's backing group; by convention it equals the organization id. */
    String getGroupId() {
        return spec.getGroupId() != null ? spec.getGroupId() : spec.getId();
    }

    private void persist() {
        OrganizationCrStore.save(spec);
    }

    @Override
    public String getId() {
        return spec.getId();
    }

    @Override
    public String getName() {
        return spec.getName();
    }

    @Override
    public void setName(String name) {
        spec.setName(name);
        persist();
    }

    @Override
    public String getAlias() {
        return spec.getAlias();
    }

    @Override
    public void setAlias(String alias) {
        if (StringUtil.isBlank(alias)) {
            alias = getName();
        }
        if (Objects.equals(alias, spec.getAlias())) {
            return;
        }
        if (StringUtil.isNotBlank(spec.getAlias())) {
            throw new ModelValidationException("Cannot change the alias");
        }
        spec.setAlias(alias);
        persist();
    }

    @Override
    public boolean isEnabled() {
        return provider.isEnabled() && spec.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        spec.setEnabled(enabled);
        persist();
    }

    @Override
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public void setDescription(String description) {
        spec.setDescription(description);
        persist();
    }

    @Override
    public String getRedirectUrl() {
        return spec.getRedirectUrl();
    }

    @Override
    public void setRedirectUrl(String redirectUrl) {
        spec.setRedirectUrl(StringUtil.isNullOrEmpty(redirectUrl) ? null : redirectUrl);
        persist();
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        Map<String, List<String>> attributes = spec.getAttributes();
        return attributes == null ? Map.of() : attributes;
    }

    @Override
    public void setAttributes(Map<String, List<String>> attributes) {
        if (attributes == null) {
            return;
        }
        Map<String, List<String>> copy = new HashMap<>();
        attributes.forEach((key, values) -> copy.put(key, values == null ? null : new ArrayList<>(values)));
        spec.setAttributes(copy);
        persist();
    }

    @Override
    public Stream<OrganizationDomainModel> getDomains() {
        Set<OrganizationDomainRepresentation> domains = spec.getDomains();
        if (domains == null) {
            return Stream.empty();
        }
        return Set.copyOf(domains).stream()
                .map(domain -> new OrganizationDomainModel(domain.getName(), domain.isVerified()));
    }

    @Override
    public void setDomains(Set<OrganizationDomainModel> domains) {
        if (domains == null) {
            return;
        }
        Map<String, OrganizationDomainModel> modelMap = domains.stream()
                .map(this::validateDomain)
                .collect(Collectors.toMap(OrganizationDomainModel::getName, domain -> domain));

        Set<OrganizationDomainRepresentation> current =
                spec.getDomains() == null ? Set.of() : new HashSet<>(spec.getDomains());
        Set<OrganizationDomainRepresentation> next = new HashSet<>();
        for (OrganizationDomainRepresentation existing : current) {
            OrganizationDomainModel model = modelMap.remove(existing.getName());
            if (model != null) {
                existing.setVerified(model.isVerified());
                next.add(existing);
            } else {
                // domain removed: strip the domain assignment from identity providers linked
                // to it, like the upstream adapter
                getIdentityProviders()
                        .filter(idp -> Objects.equals(existing.getName(),
                                idp.getConfig().get(ORGANIZATION_DOMAIN_ATTRIBUTE)))
                        .forEach(idp -> {
                            idp.getConfig().remove(ORGANIZATION_DOMAIN_ATTRIBUTE);
                            session.identityProviders().update(idp);
                        });
            }
        }
        for (OrganizationDomainModel model : modelMap.values()) {
            OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
            domain.setName(model.getName());
            domain.setVerified(model.isVerified());
            next.add(domain);
        }
        spec.setDomains(next);
        persist();
    }

    private OrganizationDomainModel validateDomain(OrganizationDomainModel domain) {
        String domainName = domain.getName();
        if (StringUtil.isBlank(domainName)) {
            throw new ModelValidationException("Domain name cannot be empty");
        }
        Organizations.validateDomain(domainName);
        OrganizationModel other = provider.getByDomainName(domainName);
        if (other != null
                && !Objects.equals(getId(), other.getId())
                && other.getDomains().anyMatch(existing -> existing.getName().equalsIgnoreCase(domainName))) {
            throw new ModelValidationException("Domain " + domainName + " is already linked to organization "
                    + other.getName() + " in realm " + realm.getName());
        }
        return domain;
    }

    @Override
    public Stream<IdentityProviderModel> getIdentityProviders() {
        return provider.getIdentityProviders(this);
    }

    @Override
    public boolean isManaged(UserModel user) {
        return provider.isManagedMember(this, user);
    }

    @Override
    public boolean isMember(UserModel user) {
        return provider.isMember(this, user);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrganizationModel other)) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return getName() + "@" + getId();
    }
}
