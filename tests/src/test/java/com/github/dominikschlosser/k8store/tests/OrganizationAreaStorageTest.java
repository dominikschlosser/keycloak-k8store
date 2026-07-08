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
package com.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakOrganizationCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import com.github.dominikschlosser.k8store.tests.config.OrganizationAreasServerConfig;
import com.github.dominikschlosser.k8store.tests.framework.Await;
import com.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import com.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import com.github.dominikschlosser.k8store.tests.framework.KindCluster;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import io.fabric8.kubernetes.client.CustomResource;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.MembershipType;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * The {@code organization} area end to end: creating an organization through the admin API
 * materializes a {@code KeycloakOrganization} CR (domains, attributes) plus its backing
 * {@code KeycloakGroup} CR ({@code spec.type: organization}), organization groups stay invisible
 * to the regular group API, membership works from both ends while living on the user side,
 * linking an identity provider lands the {@code organizationId} in the realm CR, search/list run
 * over the CR mirror, and deleting the organization cascades over the CRs without touching
 * unmanaged member users.
 */
@Order(1)
@KeycloakIntegrationTest(config = OrganizationAreasServerConfig.class)
public class OrganizationAreaStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @BeforeEach
    void enableOrganizations() {
        RealmRepresentation rep = realm.admin().toRepresentation();
        if (!Boolean.TRUE.equals(rep.isOrganizationsEnabled())) {
            rep.setOrganizationsEnabled(true);
            realm.admin().update(rep);
        }
    }

    // ------------------------------------------------------------------ CR helpers

    private <T extends CustomResource<?, ?>> List<T> crs(Class<T> type) {
        return kube.client()
                .resources(type)
                .inNamespace(namespace.name())
                .list()
                .getItems();
    }

    private Optional<KeycloakOrganizationCr> organizationCr(String orgId) {
        return crs(KeycloakOrganizationCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && orgId.equals(cr.getSpec().getId()))
                .findFirst();
    }

    private Optional<KeycloakGroupCr> groupCr(String groupId) {
        return crs(KeycloakGroupCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm())
                        && groupId.equals(cr.getSpec().getId()))
                .findFirst();
    }

    private KeycloakRealmCr realmCr() {
        return crs(KeycloakRealmCr.class).stream()
                .filter(cr -> realm.getName().equals(cr.getSpec().getRealm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KeycloakRealm CR for realm " + realm.getName()));
    }

    // ------------------------------------------------------------------ admin helpers

    private String createOrganization(String name, String alias, String domain) {
        OrganizationRepresentation rep = new OrganizationRepresentation();
        rep.setName(name);
        rep.setAlias(alias);
        rep.setEnabled(true);
        rep.setDescription(name + " description");
        rep.setRedirectUrl("https://" + domain + "/after-login");
        rep.singleAttribute("tier", "gold");
        OrganizationDomainRepresentation domainRep = new OrganizationDomainRepresentation();
        domainRep.setName(domain);
        domainRep.setVerified(true);
        rep.addDomain(domainRep);
        try (Response response = realm.admin().organizations().create(rep)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        user.setEmail(username + "@example.com");
        user.setFirstName("First");
        user.setLastName("Last");
        try (Response response = realm.admin().users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void creatingAnOrganizationMaterializesCrAndBackingGroup() {
        String orgId = createOrganization("Acme Corp", "acme", "acme.example");

        KeycloakOrganizationCr orgCr =
                organizationCr(orgId).orElseThrow(() -> new AssertionError("no KeycloakOrganization CR for " + orgId));
        assertEquals("Acme Corp", orgCr.getSpec().getName());
        assertEquals("acme", orgCr.getSpec().getAlias());
        assertTrue(orgCr.getSpec().isEnabled());
        assertEquals("Acme Corp description", orgCr.getSpec().getDescription());
        assertEquals("https://acme.example/after-login", orgCr.getSpec().getRedirectUrl());
        assertEquals(List.of("gold"), orgCr.getSpec().getAttributes().get("tier"));
        assertNotNull(orgCr.getSpec().getDomains(), "the organization CR must carry its domains");
        OrganizationDomainRepresentation domain =
                orgCr.getSpec().getDomains().iterator().next();
        assertEquals("acme.example", domain.getName());
        assertTrue(domain.isVerified());

        // the backing group is a group CR of type organization, named after the organization id
        String groupId = orgCr.getSpec().getGroupId();
        assertNotNull(groupId, "the organization CR must reference its backing group");
        KeycloakGroupCr groupCr =
                groupCr(groupId).orElseThrow(() -> new AssertionError("no backing KeycloakGroup CR " + groupId));
        assertEquals("organization", groupCr.getSpec().getType());
        assertEquals(orgId, groupCr.getSpec().getOrganizationId());
        assertEquals(orgId, groupCr.getSpec().getName(), "backing group name = organization id (upstream convention)");

        // organization groups are invisible to the regular group surface
        List<GroupRepresentation> visibleGroups = realm.admin().groups().groups();
        assertTrue(
                visibleGroups.stream().noneMatch(group -> groupId.equals(group.getId())),
                "the backing group must not surface in the regular group API: " + visibleGroups);

        // and the admin API reads the organization back through the CR store
        OrganizationRepresentation read =
                realm.admin().organizations().get(orgId).toRepresentation();
        assertEquals("Acme Corp", read.getName());
        assertEquals("acme", read.getAlias());
        assertNotNull(read.getDomain("acme.example"));
        assertEquals(List.of("gold"), read.getAttributes().get("tier"));
    }

    @Test
    public void membershipLivesOnTheUserAndIsVisibleFromBothEnds() {
        String orgId = createOrganization("Member Org", "member-org", "members.example");
        String userId = createUser("org-member");

        try (Response response =
                realm.admin().organizations().get(orgId).members().addMember(userId)) {
            assertEquals(201, response.getStatus());
        }

        // organization -> members
        List<MemberRepresentation> members =
                realm.admin().organizations().get(orgId).members().getAll();
        assertEquals(1, members.size());
        assertEquals("org-member", members.get(0).getUsername());
        assertEquals(MembershipType.UNMANAGED, members.get(0).getMembershipType(), "admin-added members are unmanaged");
        assertEquals(1, realm.admin().organizations().get(orgId).members().count());
        List<MemberRepresentation> found =
                realm.admin().organizations().get(orgId).members().search("org-member", false, null, null);
        assertEquals(1, found.size());

        // member -> organizations
        List<OrganizationRepresentation> memberOrgs =
                realm.admin().organizations().members().getOrganizations(userId, true);
        assertTrue(
                memberOrgs.stream().anyMatch(org -> orgId.equals(org.getId())),
                "the member's organizations must include the organization");

        // membership is stored on the user side: the organization CR carries no member list
        KeycloakOrganizationCr orgCr = organizationCr(orgId).orElseThrow();
        assertNull(orgCr.getSpec().getMembers(), "membership must not be embedded in the organization CR");

        // leaving the organization removes the membership but keeps the (unmanaged) user
        try (Response response =
                realm.admin().organizations().get(orgId).members().removeMember(userId)) {
            assertEquals(204, response.getStatus());
        }
        assertEquals(0, realm.admin().organizations().get(orgId).members().count());
        assertNotNull(
                realm.admin().users().get(userId).toRepresentation(),
                "removing an unmanaged member must not delete the user");
    }

    @Test
    public void identityProviderLinkageLandsInTheRealmCr() {
        String orgId = createOrganization("IdP Org", "idp-org", "idp.example");

        IdentityProviderRepresentation idp = new IdentityProviderRepresentation();
        idp.setAlias("org-broker");
        idp.setProviderId("oidc");
        idp.setEnabled(true);
        idp.setConfig(new HashMap<>(Map.of(
                "authorizationUrl", "https://broker.example/auth",
                "tokenUrl", "https://broker.example/token",
                "clientId", "kc",
                "clientSecret", "secret")));
        try (Response response = realm.admin().identityProviders().create(idp)) {
            assertEquals(201, response.getStatus());
        }

        try (Response response =
                realm.admin().organizations().get(orgId).identityProviders().addIdentityProvider("org-broker")) {
            assertEquals(204, response.getStatus());
        }

        // linkage visible through the organization API
        List<IdentityProviderRepresentation> linked =
                realm.admin().organizations().get(orgId).identityProviders().getIdentityProviders();
        assertEquals(1, linked.size());
        assertEquals("org-broker", linked.get(0).getAlias());
        assertEquals(orgId, linked.get(0).getOrganizationId());

        // and persisted where the model keeps it: on the identity provider in the realm CR
        Await.await(
                "realm CR to carry the organization linkage on the identity provider",
                () -> realmCr().getSpec().getIdentityProviders() != null
                        && realmCr().getSpec().getIdentityProviders().stream()
                                .anyMatch(rep ->
                                        "org-broker".equals(rep.getAlias()) && orgId.equals(rep.getOrganizationId())));

        // the organization CR itself embeds no identity providers
        assertNull(organizationCr(orgId).orElseThrow().getSpec().getIdentityProviders());
    }

    @Test
    public void searchAndListRunOverTheCrMirror() {
        String orgId = createOrganization("Search Org", "search-org", "search.example");
        createOrganization("Other Org", "other-org", "other.example");

        List<OrganizationRepresentation> all = realm.admin().organizations().getAll();
        assertTrue(all.size() >= 2);

        List<OrganizationRepresentation> byName = realm.admin().organizations().search("Search");
        assertTrue(
                byName.stream().anyMatch(org -> orgId.equals(org.getId())),
                "name search must find the organization: " + byName);

        List<OrganizationRepresentation> byDomain =
                realm.admin().organizations().search("search.example");
        assertTrue(
                byDomain.stream().anyMatch(org -> orgId.equals(org.getId())),
                "domain search must find the organization: " + byDomain);

        List<OrganizationRepresentation> byExactName =
                realm.admin().organizations().search("Search Org", true, 0, 10);
        assertEquals(1, byExactName.size());

        List<OrganizationRepresentation> byAttribute =
                realm.admin().organizations().searchByAttribute("tier:gold");
        assertTrue(
                byAttribute.stream().anyMatch(org -> orgId.equals(org.getId())),
                "attribute search must find the organization: " + byAttribute);
    }

    @Test
    public void deletingAnOrganizationCascadesOverItsCrs() {
        String orgId = createOrganization("Doomed Org", "doomed-org", "doomed.example");
        String userId = createUser("doomed-org-member");
        try (Response response =
                realm.admin().organizations().get(orgId).members().addMember(userId)) {
            assertEquals(201, response.getStatus());
        }
        String groupId = organizationCr(orgId).orElseThrow().getSpec().getGroupId();
        assertTrue(groupCr(groupId).isPresent());

        try (Response response = realm.admin().organizations().get(orgId).delete()) {
            assertEquals(204, response.getStatus());
        }

        Await.await("organization CR to be deleted", () -> organizationCr(orgId).isEmpty());
        Await.await("backing group CR to be deleted with the organization", () -> groupCr(groupId)
                .isEmpty());
        assertNotNull(
                realm.admin().users().get(userId).toRepresentation(),
                "unmanaged members survive the organization removal");
        assertFalse(realm.admin().organizations().getAll().stream().anyMatch(org -> orgId.equals(org.getId())));
    }
}
