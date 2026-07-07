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

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.crd.OrganizationInvitationSpec;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationInvitationModel;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.organization.InvitationManager;
import org.keycloak.representations.idm.OrganizationInvitationRepresentation;
import org.keycloak.utils.StreamsUtil;

/**
 * {@link InvitationManager} over {@code KeycloakOrganizationInvitation} custom resources.
 * Semantics mirror the upstream JPA manager: expiry = creation time plus the realm's admin
 * action token lifespan, the status filter distinguishes pending from expired by comparing
 * {@code expiresAt} to the current time (expired invitations stay stored and listable), the
 * name/email filters match case-insensitively and the search filter is a contains match with
 * {@code *} wildcards over email and names.
 */
public class CrInvitationManager implements InvitationManager {

    private final KeycloakSession session;

    CrInvitationManager(KeycloakSession session) {
        this.session = session;
    }

    private RealmModel realm() {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            throw new IllegalArgumentException("Session not bound to a realm");
        }
        return realm;
    }

    @Override
    public OrganizationInvitationModel create(OrganizationModel organization, String email, String firstName,
            String lastName) {
        OrganizationInvitationSpec spec = new OrganizationInvitationSpec();
        spec.setId(KeycloakModelUtils.generateId());
        spec.setRealm(realm().getId());
        spec.setOrganizationId(organization.getId());
        spec.setEmail(email.trim());
        spec.setFirstName(firstName == null ? null : firstName.trim());
        spec.setLastName(lastName == null ? null : lastName.trim());
        spec.setCreatedAt(Time.currentTime());
        spec.setExpiresAt(Time.currentTime() + realm().getActionTokenGeneratedByAdminLifespan());
        OrganizationInvitationCrStore.save(spec);
        return new OrganizationInvitationAdapter(spec);
    }

    @Override
    public OrganizationInvitationModel getById(String id) {
        OrganizationInvitationSpec spec = OrganizationInvitationCrStore.read(realm().getId(), id);
        return spec == null ? null : new OrganizationInvitationAdapter(spec);
    }

    @Override
    public Stream<OrganizationInvitationModel> getAllStream(OrganizationModel organization,
            Map<OrganizationInvitationModel.Filter, String> filters, Integer first, Integer max) {
        Stream<OrganizationInvitationSpec> specs = OrganizationInvitationCrStore.allInRealm(realm().getId())
                .stream()
                .filter(spec -> organization.getId().equals(spec.getOrganizationId()));
        if (filters != null) {
            for (Map.Entry<OrganizationInvitationModel.Filter, String> filter : filters.entrySet()) {
                specs = specs.filter(matches(filter.getKey(), filter.getValue()));
            }
        }
        Stream<OrganizationInvitationModel> models = specs
                .sorted(Comparator.comparing(OrganizationInvitationSpec::getId))
                .map(OrganizationInvitationAdapter::new)
                .map(OrganizationInvitationModel.class::cast);
        return StreamsUtil.paginatedStream(models, first, max);
    }

    private static Predicate<OrganizationInvitationSpec> matches(OrganizationInvitationModel.Filter filter,
            String value) {
        switch (filter) {
            case EMAIL:
                return spec -> spec.getEmail() != null && spec.getEmail().equalsIgnoreCase(value);
            case FIRST_NAME:
                return spec -> spec.getFirstName() != null && spec.getFirstName().equalsIgnoreCase(value);
            case LAST_NAME:
                return spec -> spec.getLastName() != null && spec.getLastName().equalsIgnoreCase(value);
            case STATUS:
                OrganizationInvitationRepresentation.Status status =
                        OrganizationInvitationRepresentation.Status.valueOf(value);
                if (OrganizationInvitationRepresentation.Status.EXPIRED.equals(status)) {
                    return spec -> expiresAt(spec) < Time.currentTime();
                }
                return spec -> expiresAt(spec) >= Time.currentTime();
            case SEARCH:
                return spec -> LikePatterns.containsTerm(spec.getEmail(), value, false)
                        || LikePatterns.containsTerm(spec.getFirstName(), value, false)
                        || LikePatterns.containsTerm(spec.getLastName(), value, false);
            default:
                return spec -> true;
        }
    }

    private static int expiresAt(OrganizationInvitationSpec spec) {
        return spec.getExpiresAt() == null ? 0 : spec.getExpiresAt();
    }

    @Override
    public boolean remove(String id) {
        OrganizationInvitationSpec spec = OrganizationInvitationCrStore.read(realm().getId(), id);
        if (spec == null) {
            return false;
        }
        OrganizationInvitationCrStore.delete(spec.getRealm(), spec.getId());
        return true;
    }

    /** Removes all invitations of an organization (organization/realm removal cascade). */
    void removeAll(RealmModel realm, String organizationId) {
        OrganizationInvitationCrStore.allInRealm(realm.getId()).stream()
                .filter(spec -> organizationId == null || organizationId.equals(spec.getOrganizationId()))
                .forEach(spec -> OrganizationInvitationCrStore.delete(spec.getRealm(), spec.getId()));
    }
}
