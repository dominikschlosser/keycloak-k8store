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

import static org.keycloak.utils.StreamsUtil.paginatedStream;

import com.github.dominikschlosser.k8store.common.LikePatterns;
import com.github.dominikschlosser.k8store.crd.GroupSpec;
import com.github.dominikschlosser.k8store.crd.OrganizationSpec;
import com.github.dominikschlosser.k8store.group.GroupAdapter;
import com.github.dominikschlosser.k8store.group.GroupCrProvider;
import com.github.dominikschlosser.k8store.group.GroupCrStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.models.GroupModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.MembershipMetadata;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ModelException;
import org.keycloak.models.ModelValidationException;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.ReadOnlyUserModelDelegate;
import org.keycloak.organization.InvitationManager;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.utils.Organizations;
import org.keycloak.representations.idm.MembershipType;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.utils.ReservedCharValidator;
import org.keycloak.utils.StringUtil;

/**
 * {@link OrganizationProvider} serving organizations from {@code KeycloakOrganization} custom
 * resources. The storage split mirrors the upstream model exactly:
 *
 * <ul>
 *   <li>the organization <em>definition</em> (name, alias, enabled, description, redirect URL,
 *       domains, attributes) is the {@code KeycloakOrganization} CR - configuration, rejected in
 *       read-only mode;
 *   <li>the backing group and organization-scoped subgroups are created through
 *       {@code session.groups()} as {@code KeycloakGroup} CRs with {@code spec.type:
 *       organization} (backing group name = organization id, upstream convention);
 *   <li><em>membership</em> is group membership of the backing group and lives wherever users
 *       live (database rows or {@code KeycloakUser} CRs) - always runtime-writable, so
 *       invitation/registration/broker joins keep working when organization CRs are read-only.
 *       The MANAGED marker is the multi-valued user attribute
 *       {@link #MANAGED_MEMBER_ATTRIBUTE} (upstream stores it in a membership column that no
 *       model API can read back);
 *   <li>the identity-provider linkage is the {@code organizationId} field of the identity
 *       provider, stored in the realm CR;
 *   <li>invitations are {@code KeycloakOrganizationInvitation} CRs - runtime data, writable in
 *       read-only mode.
 * </ul>
 */
public class OrganizationCrProvider implements OrganizationProvider {

    /**
     * Multi-valued user attribute holding the ids of the organizations the user is a MANAGED
     * member of (the user's lifecycle is bound to the organization: removal from the
     * organization deletes the user). Written by {@link #addManagedMember}; lives on the user so
     * managed members can be onboarded at runtime (first broker login) even when organization
     * CRs are read-only. With Keycloak's default unmanaged-attribute policy the attribute is
     * invisible to and preserved through user-profile updates.
     */
    public static final String MANAGED_MEMBER_ATTRIBUTE = "k8store.org.managed";

    private final KeycloakSession session;
    private final Map<String, OrganizationModel> knownAdapters = new HashMap<>();
    private CrInvitationManager invitationManager;

    public OrganizationCrProvider(KeycloakSession session) {
        this.session = session;
    }

    private RealmModel realm() {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            throw new IllegalArgumentException("Session not bound to a realm");
        }
        return realm;
    }

    /** Memoizes adapters per id so one flow never mutates two spec copies of one organization. */
    private OrganizationModel adapt(RealmModel realm, OrganizationSpec spec) {
        return knownAdapters.computeIfAbsent(realm.getId() + "/" + spec.getId(),
                key -> new OrganizationAdapter(session, realm, spec, this));
    }

    private Stream<OrganizationSpec> specs(RealmModel realm) {
        return OrganizationCrStore.allInRealm(realm.getId()).stream();
    }

    private OrganizationAdapter entity(OrganizationModel organization) {
        return entity(organization.getId());
    }

    /** Resolves the organization or throws, like the upstream provider's getEntity. */
    private OrganizationAdapter entity(String id) {
        OrganizationModel model = getById(id);
        if (model == null) {
            throw new ModelException("Organization [" + id + "] does not exist");
        }
        return (OrganizationAdapter) model;
    }

    private static void throwIfNull(Object object, String objectName) {
        if (object == null) {
            throw new ModelException(String.format("%s cannot be null", objectName));
        }
    }

    /**
     * Runs an action with the session-context organization set (unless a context organization
     * already exists) - the upstream JPA factory registers a global group-event guard that
     * rejects organization-group mutations outside an organization context, and validators
     * resolve the context organization the same way.
     */
    private <T> T withOrganizationContext(OrganizationModel organization, Supplier<T> action) {
        OrganizationModel current = session.getContext().getOrganization();
        if (current == null) {
            session.getContext().setOrganization(organization);
        }
        try {
            return action.get();
        } finally {
            if (current == null) {
                session.getContext().setOrganization(null);
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public OrganizationModel create(String id, String name, String alias) {
        if (StringUtil.isBlank(name)) {
            throw new ModelValidationException("Name can not be null");
        }
        if (StringUtil.isBlank(alias)) {
            try {
                ReservedCharValidator.validateNoSpace(name);
            } catch (ReservedCharValidator.ReservedCharException e) {
                throw new ModelValidationException("Name cannot be used as alias: " + e.getMessage());
            }
            alias = name;
        }
        RealmModel realm = realm();
        if (specs(realm).anyMatch(spec -> name.equals(spec.getName()))) {
            throw new ModelDuplicateException("A organization with the same name already exists.");
        }
        String effectiveAlias = alias;
        if (specs(realm).anyMatch(spec -> effectiveAlias.equals(spec.getAlias()))) {
            throw new ModelDuplicateException("A organization with the same alias already exists");
        }

        OrganizationSpec spec = new OrganizationSpec();
        spec.setId(id != null ? id : KeycloakModelUtils.generateId());
        spec.setRealm(realm.getId());
        spec.setName(name);
        spec.setAlias(alias);
        spec.setEnabled(true);
        OrganizationModel adapter = new OrganizationAdapter(session, realm, spec, this);
        knownAdapters.put(realm.getId() + "/" + spec.getId(), adapter);
        return withOrganizationContext(adapter, () -> {
            // the backing group lands wherever the group area stores groups; its name is the
            // organization id (upstream convention) and its id follows this store's id = name
            // convention, so spec.groupId is the organization id too
            GroupModel group = session.groups()
                    .createGroup(realm, null, GroupModel.Type.ORGANIZATION, spec.getId(), null);
            linkGroup(group, spec.getId());
            spec.setGroupId(group.getId());
            OrganizationCrStore.save(spec);
            return adapter;
        });
    }

    /**
     * Stamps the owning organization on a freshly created group - through the returned
     * adapter's live spec, never a fresh store copy (an in-flight adapter would clobber a
     * concurrent mutation on its next persist).
     */
    private void linkGroup(GroupModel group, String organizationId) {
        if (!(group instanceof GroupAdapter adapter)) {
            throw new ModelException("k8store organizations require CR-backed groups (the 'group' area)");
        }
        adapter.linkOrganization(organizationId);
    }

    @Override
    public boolean remove(OrganizationModel organization) {
        OrganizationAdapter entity = entity(organization);
        RealmModel realm = entity.realm();
        withOrganizationContext(organization, () -> {
            GroupModel group = getOrganizationGroupOrNull(entity);
            if (group != null) {
                // managed members are removed with the organization (their lifecycle is bound
                // to it), unmanaged members just leave the backing group
                session.users().getGroupMembersStream(realm, group)
                        .collect(Collectors.toList())
                        .forEach(member -> removeMember(organization, member));
                session.groups().removeGroup(realm, group);
            }
            organization.getIdentityProviders()
                    .collect(Collectors.toList())
                    .forEach(broker -> removeIdentityProvider(organization, broker));
            invitationManager().removeAll(realm, organization.getId());
            OrganizationModel.OrganizationRemovedEvent.fire(organization, session);
            OrganizationCrStore.delete(realm.getId(), organization.getId());
            knownAdapters.remove(realm.getId() + "/" + organization.getId());
            return null;
        });
        return true;
    }

    @Override
    public void removeAll() {
        getAllStream().collect(Collectors.toList()).forEach(this::remove);
    }

    /** Realm-removal cascade: bulk-delete the realm's organization and invitation CRs. */
    void realmRemoved(RealmModel realm) {
        OrganizationCrStore.allInRealm(realm.getId())
                .forEach(spec -> OrganizationCrStore.delete(spec.getRealm(), spec.getId()));
        invitationManager().removeAll(realm, null);
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public OrganizationModel getById(String id) {
        if (id == null) {
            return null;
        }
        RealmModel realm = realm();
        OrganizationModel known = knownAdapters.get(realm.getId() + "/" + id);
        if (known != null) {
            return known;
        }
        OrganizationSpec spec = OrganizationCrStore.read(realm.getId(), id);
        return spec == null ? null : adapt(realm, spec);
    }

    @Override
    public OrganizationModel getByDomainName(String domain) {
        if (domain == null) {
            return null;
        }
        String emailDomain = domain.toLowerCase();
        Organizations.validateDomain(emailDomain);
        RealmModel realm = realm();
        List<OrganizationModel> candidates = specs(realm)
                .filter(spec -> spec.getDomains() != null && spec.getDomains().stream()
                        .anyMatch(d -> Organizations.isSameDomain(emailDomain, d.getName())))
                .map(spec -> adapt(realm, spec))
                .collect(Collectors.toList());
        return Organizations.resolveByDomain(candidates, emailDomain);
    }

    @Override
    public Stream<OrganizationModel> getAllStream(String search, Boolean exact, Integer first, Integer max) {
        RealmModel realm = realm();
        Stream<OrganizationSpec> specs = specs(realm);
        if (StringUtil.isNotBlank(search)) {
            specs = specs.filter(spec -> nameOrDomainMatches(spec, search, Boolean.TRUE.equals(exact)));
        }
        return paginatedStream(specs
                .sorted(Comparator.comparing(OrganizationSpec::getName, Comparator.nullsLast(String::compareTo)))
                .map(spec -> adapt(realm, spec)), first, max);
    }

    private static boolean nameOrDomainMatches(OrganizationSpec spec, String search, boolean exact) {
        Set<OrganizationDomainRepresentation> domains = spec.getDomains() == null ? Set.of() : spec.getDomains();
        if (exact) {
            return search.equals(spec.getName())
                    || domains.stream().anyMatch(domain -> search.equals(domain.getName()));
        }
        String pattern = "%" + search + "%";
        return LikePatterns.insensitiveLike(spec.getName(), pattern)
                || domains.stream().anyMatch(domain -> LikePatterns.insensitiveLike(domain.getName(), pattern));
    }

    @Override
    public Stream<OrganizationModel> getAllStream(Map<String, String> attributes, Integer first, Integer max) {
        RealmModel realm = realm();
        Stream<OrganizationSpec> specs = specs(realm);
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (StringUtil.isBlank(entry.getKey())) {
                    continue;
                }
                if (OrganizationModel.ALIAS.equals(entry.getKey())) {
                    specs = specs.filter(spec -> Objects.equals(entry.getValue(), spec.getAlias()));
                } else {
                    specs = specs.filter(spec -> spec.getAttributes() != null
                            && spec.getAttributes().getOrDefault(entry.getKey(), List.of())
                                    .contains(entry.getValue()));
                }
            }
        }
        return paginatedStream(specs
                .sorted(Comparator.comparing(OrganizationSpec::getName, Comparator.nullsLast(String::compareTo)))
                .map(spec -> adapt(realm, spec)), first, max);
    }

    @Override
    public long count() {
        return OrganizationCrStore.allInRealm(realm().getId()).size();
    }

    @Override
    public boolean hasOrganizations() {
        return !OrganizationCrStore.allInRealm(realm().getId()).isEmpty();
    }

    @Override
    public boolean isEnabled() {
        return realm().isOrganizationsEnabled();
    }

    // ------------------------------------------------------------------ members

    @Override
    public boolean addManagedMember(OrganizationModel organization, UserModel user) {
        return addMember(organization, user, MembershipType.MANAGED);
    }

    @Override
    public boolean addMember(OrganizationModel organization, UserModel user) {
        return addMember(organization, user, MembershipType.UNMANAGED);
    }

    private boolean addMember(OrganizationModel organization, UserModel user, MembershipType membershipType) {
        throwIfNull(organization, "Organization");
        throwIfNull(user, "User");
        OrganizationAdapter entity = entity(organization);
        RealmModel realm = entity.realm();
        if (session.users().getUserById(realm, user.getId()) == null) {
            return false;
        }
        return withOrganizationContext(organization, () -> {
            GroupModel group = getOrganizationGroup(entity);
            if (user.isMemberOf(group)) {
                return false;
            }
            user.joinGroup(group, new MembershipMetadata(membershipType));
            if (MembershipType.MANAGED.equals(membershipType)) {
                List<String> managed = user.getAttributeStream(MANAGED_MEMBER_ATTRIBUTE)
                        .collect(Collectors.toCollection(ArrayList::new));
                if (!managed.contains(organization.getId())) {
                    managed.add(organization.getId());
                    user.setAttribute(MANAGED_MEMBER_ATTRIBUTE, managed);
                }
            }
            OrganizationModel.OrganizationMemberJoinEvent.fire(organization, user, session);
            return true;
        });
    }

    @Override
    public Stream<UserModel> getMembersStream(OrganizationModel organization, String search, Boolean exact,
            Integer first, Integer max) {
        throwIfNull(organization, "Organization");
        GroupModel group = getOrganizationGroup(organization);
        RealmModel realm = realm();
        Stream<UserModel> members = search == null
                ? session.users().getGroupMembersStream(realm, group, first, max)
                : session.users().getGroupMembersStream(realm, group, search, exact, first, max);
        return members.map(this::readOnlyIfDisabledOrgMember);
    }

    /**
     * Members of a disabled organization (and managed members while the organizations switch is
     * off) are served read-only and disabled, upstream parity.
     */
    private UserModel readOnlyIfDisabledOrgMember(UserModel user) {
        if (!Organizations.isReadOnlyOrganizationMember(session, user)) {
            return user;
        }
        return new ReadOnlyUserModelDelegate(user) {
            @Override
            public boolean isEnabled() {
                return false;
            }
        };
    }

    @Override
    public long getMembersCount(OrganizationModel organization) {
        throwIfNull(organization, "Organization");
        GroupModel group = getOrganizationGroup(organization);
        return session.users().getGroupMembersStream(realm(), group).count();
    }

    @Override
    public UserModel getMemberById(OrganizationModel organization, String id) {
        throwIfNull(organization, "Organization");
        UserModel user = session.users().getUserById(realm(), id);
        if (user == null) {
            return null;
        }
        return getByMember(user).anyMatch(organization::equals) ? user : null;
    }

    @Override
    public Stream<OrganizationModel> getByMember(UserModel member) {
        throwIfNull(member, "User");
        RealmModel realm = realm();
        Set<String> directGroupIds = member.getGroupsStream()
                .map(GroupModel::getId)
                .collect(Collectors.toSet());
        return specs(realm)
                .filter(spec -> directGroupIds.contains(backingGroupId(spec)))
                .sorted(Comparator.comparing(OrganizationSpec::getName, Comparator.nullsLast(String::compareTo)))
                .map(spec -> adapt(realm, spec));
    }

    @Override
    public boolean isManagedMember(OrganizationModel organization, UserModel member) {
        throwIfNull(organization, "organization");
        if (member == null) {
            return false;
        }
        return member.getAttributeStream(MANAGED_MEMBER_ATTRIBUTE)
                .anyMatch(orgId -> orgId.equals(organization.getId()));
    }

    @Override
    public boolean removeMember(OrganizationModel organization, UserModel member) {
        throwIfNull(organization, "organization");
        throwIfNull(member, "member");
        if (getByMember(member).noneMatch(organization::equals)) {
            return false;
        }
        if (isManagedMember(organization, member)) {
            session.users().removeUser(realm(), member);
        } else {
            withOrganizationContext(organization, () -> {
                getOrganizationGroupsByMember(organization, member)
                        .collect(Collectors.toList())
                        .forEach(member::leaveGroup);
                member.leaveGroup(getOrganizationGroup(organization));
                return null;
            });
        }
        OrganizationModel.OrganizationMemberLeaveEvent.fire(organization, member, session);
        return true;
    }

    // ------------------------------------------------------------------ organization groups

    private static String backingGroupId(OrganizationSpec spec) {
        return spec.getGroupId() != null ? spec.getGroupId() : spec.getId();
    }

    @Override
    public GroupModel getOrganizationGroup(OrganizationModel organization) {
        throwIfNull(organization, "Organization");
        OrganizationAdapter entity = entity(organization);
        GroupModel group = getOrganizationGroupOrNull(entity);
        if (group == null) {
            throw new ModelException("Organization group " + entity.getGroupId() + " not found");
        }
        return group;
    }

    private GroupModel getOrganizationGroupOrNull(OrganizationAdapter entity) {
        return session.groups().getGroupById(entity.realm(), entity.getGroupId());
    }

    @Override
    public GroupModel createGroup(OrganizationModel organization, String id, String name, GroupModel toParent) {
        throwIfNull(name, "Name");
        OrganizationAdapter entity = entity(organization);
        GroupModel parent;
        if (toParent == null) {
            parent = getOrganizationGroup(organization);
        } else {
            if (!Organizations.isOrganizationGroup(toParent)
                    || !Objects.equals(toParent.getOrganization().getId(), organization.getId())) {
                throw new ModelValidationException("Parent group does not belong to the specified organization");
            }
            parent = toParent;
        }
        GroupModel created = session.groups()
                .createGroup(entity.realm(), id, GroupModel.Type.ORGANIZATION, name, parent);
        linkGroup(created, organization.getId());
        return created;
    }

    /** Organization-scoped groups of the organization, excluding the backing group itself. */
    private Stream<GroupSpec> organizationGroupSpecs(OrganizationModel organization) {
        OrganizationAdapter entity = entity(organization);
        String backingGroupId = entity.getGroupId();
        return GroupCrStore.allInRealm(entity.realm().getId()).stream()
                .filter(spec -> GroupCrProvider.ORGANIZATION_TYPE.equalsIgnoreCase(spec.getType()))
                .filter(spec -> organization.getId().equals(spec.getOrganizationId()))
                .filter(spec -> !backingGroupId.equals(spec.getId()));
    }

    private Stream<GroupModel> toGroups(RealmModel realm, Stream<GroupSpec> specs) {
        return specs
                .sorted(Comparator.comparing(GroupSpec::getName, Comparator.nullsLast(String::compareTo)))
                .map(spec -> session.groups().getGroupById(realm, spec.getId()))
                .filter(Objects::nonNull);
    }

    @Override
    public Stream<GroupModel> getTopLevelGroups(OrganizationModel organization, Integer first, Integer max) {
        throwIfNull(organization, "Organization");
        OrganizationAdapter entity = entity(organization);
        String backingGroupId = entity.getGroupId();
        return paginatedStream(toGroups(entity.realm(), organizationGroupSpecs(organization)
                .filter(spec -> backingGroupId.equals(spec.getParentId()))), first, max);
    }

    @Override
    public Stream<GroupModel> searchGroupsByName(OrganizationModel organization, String search, Boolean exact,
            Integer first, Integer max) {
        throwIfNull(organization, "Organization");
        OrganizationAdapter entity = entity(organization);
        Stream<GroupSpec> specs = organizationGroupSpecs(organization)
                .filter(spec -> groupNameMatches(spec.getName(), search, exact));
        return paginatedStream(toGroups(entity.realm(), specs), first, max);
    }

    /**
     * Upstream's group-name search is a prefix match unless the search carries {@code *}
     * wildcards (case-insensitive; exact = equality).
     */
    private static boolean groupNameMatches(String name, String search, Boolean exact) {
        if (search == null) {
            return true;
        }
        if (Boolean.TRUE.equals(exact)) {
            return search.equals(name);
        }
        String pattern = search.replace('*', '%');
        if (pattern.isEmpty() || pattern.charAt(pattern.length() - 1) != '%') {
            pattern = pattern + "%";
        }
        return LikePatterns.insensitiveLike(name, pattern);
    }

    @Override
    public Stream<GroupModel> searchGroupsByAttributes(OrganizationModel organization, Map<String, String> attributes,
            Integer first, Integer max) {
        throwIfNull(organization, "Organization");
        OrganizationAdapter entity = entity(organization);
        Stream<GroupSpec> specs = organizationGroupSpecs(organization);
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    continue;
                }
                specs = specs.filter(spec -> spec.getAttributes() != null
                        && spec.getAttributes().getOrDefault(entry.getKey(), List.of()).stream()
                                .anyMatch(value -> value != null && value.equalsIgnoreCase(entry.getValue())));
            }
        }
        return paginatedStream(toGroups(entity.realm(), specs), first, max);
    }

    @Override
    public Stream<GroupModel> getOrganizationGroupsByMember(OrganizationModel organization, UserModel member) {
        return getOrganizationGroupsByMember(organization, member, null, null, null);
    }

    @Override
    public Stream<GroupModel> getOrganizationGroupsByMember(OrganizationModel organization, UserModel member,
            String search, Integer first, Integer max) {
        throwIfNull(organization, "Organization");
        throwIfNull(member, "Member");
        if (!isMember(organization, member)) {
            return Stream.empty();
        }
        OrganizationAdapter entity = entity(organization);
        Set<String> directGroupIds = member.getGroupsStream()
                .map(GroupModel::getId)
                .collect(Collectors.toSet());
        Stream<GroupSpec> specs = organizationGroupSpecs(organization)
                .filter(spec -> directGroupIds.contains(spec.getId()));
        if (search != null && !search.isBlank()) {
            specs = specs.filter(spec -> groupNameMatches(spec.getName(), search.trim(), false));
        }
        return paginatedStream(toGroups(entity.realm(), specs), first, max);
    }

    // ------------------------------------------------------------------ identity providers

    @Override
    public boolean addIdentityProvider(OrganizationModel organization, IdentityProviderModel identityProvider) {
        throwIfNull(organization, "Organization");
        throwIfNull(identityProvider, "Identity provider");
        OrganizationAdapter entity = entity(organization);
        IdentityProviderModel byAlias = session.identityProviders().getByAlias(identityProvider.getAlias());
        if (byAlias == null || !Objects.equals(byAlias.getInternalId(), identityProvider.getInternalId())) {
            return false;
        }
        String linkedOrgId = identityProvider.getOrganizationId();
        if (entity.getId().equals(linkedOrgId)) {
            return false;
        }
        if (linkedOrgId != null) {
            throw new ModelValidationException("Identity provider already associated with a different organization");
        }
        identityProvider.setOrganizationId(entity.getId());
        session.identityProviders().update(identityProvider);
        return true;
    }

    @Override
    public Stream<IdentityProviderModel> getIdentityProviders(OrganizationModel organization) {
        throwIfNull(organization, "Organization");
        throwIfNull(organization.getId(), "Organization ID");
        entity(organization);
        return session.identityProviders().getByOrganization(organization.getId(), null, null);
    }

    @Override
    public boolean removeIdentityProvider(OrganizationModel organization, IdentityProviderModel identityProvider) {
        throwIfNull(organization, "Organization");
        OrganizationAdapter entity = entity(organization);
        if (!entity.getId().equals(identityProvider.getOrganizationId())) {
            return false;
        }
        identityProvider.setOrganizationId(null);
        identityProvider.getConfig().remove(OrganizationModel.ORGANIZATION_DOMAIN_ATTRIBUTE);
        session.identityProviders().update(identityProvider);
        return true;
    }

    // ------------------------------------------------------------------ invitations

    private CrInvitationManager invitationManager() {
        if (invitationManager == null) {
            invitationManager = new CrInvitationManager(session);
        }
        return invitationManager;
    }

    @Override
    public InvitationManager getInvitationManager() {
        return invitationManager();
    }

    @Override
    public void close() {
        // stateless facade over the informer-backed store
    }
}
