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
package com.github.dominikschlosser.k8store.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.crd.GroupSpec;
import com.github.dominikschlosser.k8store.crd.OrganizationInvitationSpec;
import com.github.dominikschlosser.k8store.crd.OrganizationSpec;
import com.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakOrganizationCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakOrganizationInvitationCr;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.Profile;
import org.keycloak.common.util.Time;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.storage.ReadOnlyException;

/**
 * Organization-area behavior of the storage backend: the area is opt-in (not part of the config
 * default but part of {@code all}), requires the group and identity-provider areas, gates the
 * registration of its two kinds, splits writability between the config-class organization kind
 * and the always-writable invitations, round-trips the organization spec (domains, attributes)
 * while excluding the embedded members/groups/identityProviders collections, and the boot gate
 * rejects the broken combination "organizations feature on + CR groups + organization area off".
 */
@EnableKubernetesMockClient(crud = true)
class K8sOrganizationKindsTest {

    KubernetesClient client;

    @AfterEach
    void shutdownBackend() {
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();
        Profile.reset();
    }

    private K8sStorageBackend start(boolean readOnly, Set<Area> areas) {
        return K8sStorageBackend.initWithClient(client, K8sStoreConfig.of(readOnly, areas, "test", false, 30));
    }

    // ------------------------------------------------------------------ area grammar

    @Test
    void organizationAreaIsOptInButJoinsAll() {
        assertFalse(
                K8sStoreConfig.configAreas().contains(Area.ORGANIZATION),
                "the config default must stay backward-compatible - organization is opt-in");
        assertFalse(K8sStoreConfig.parseAreas("config").contains(Area.ORGANIZATION));
        assertTrue(K8sStoreConfig.parseAreas("all").contains(Area.ORGANIZATION), "the organization area joins 'all'");
        assertTrue(
                K8sStoreConfig.parseAreas("realm,client,client-scope,role,group,identity-provider,organization")
                        .contains(Area.ORGANIZATION),
                "explicit lists may name the organization area");
        assertFalse(
                Area.ORGANIZATION.isDynamic(),
                "organization definitions are configuration-class (read-only mode applies)");
    }

    @Test
    void organizationAreaAutoActivatesGroupIdentityProviderAndRealm() {
        // organizations are group-backed (Keycloak's JPA org store even does a raw em.find on the
        // backing GroupEntity) and their IdP linkage lives in the realm CR, so naming just the
        // organization area pulls in group, identity-provider and (transitively) realm.
        Set<Area> resolved = K8sStoreConfig.of(false, EnumSet.of(Area.ORGANIZATION), "test", false, 30)
                .getAreas();
        assertTrue(resolved.contains(Area.GROUP), "the organization area must pull in the group area");
        assertTrue(
                resolved.contains(Area.IDENTITY_PROVIDER),
                "the organization area must pull in the identity-provider area");
        assertTrue(
                resolved.contains(Area.REALM),
                "identity-provider pulls in realm transitively (IdPs are embedded in the realm CR)");
        // parseAreas is the raw grammar; dependency expansion is applied on top, not by it
        assertTrue(K8sStoreConfig.withDependencies(K8sStoreConfig.parseAreas("organization"))
                .containsAll(EnumSet.of(Area.ORGANIZATION, Area.GROUP, Area.IDENTITY_PROVIDER, Area.REALM)));
    }

    // ------------------------------------------------------------------ feature boot gate

    @Test
    void featureWithCrGroupsButWithoutOrganizationAreaIsRejected() {
        Profile.init(Profile.ProfileName.DEFAULT, Map.of(Profile.Feature.ORGANIZATION, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> K8sStoreConfig.validateOrganizationFeatureCoupling(K8sStoreConfig.configAreas()),
                "the JPA organization store cannot reference CR-backed groups - boot must fail clearly");
        // adding the organization area resolves it
        Set<Area> withOrganization = EnumSet.copyOf(K8sStoreConfig.configAreas());
        withOrganization.add(Area.ORGANIZATION);
        K8sStoreConfig.validateOrganizationFeatureCoupling(withOrganization);
        // groups on JPA (no CR group area) never trip the gate
        K8sStoreConfig.validateOrganizationFeatureCoupling(EnumSet.of(Area.REALM, Area.CLIENT));
        // feature disabled never trips the gate
        Profile.init(Profile.ProfileName.DEFAULT, Map.of(Profile.Feature.ORGANIZATION, false));
        K8sStoreConfig.validateOrganizationFeatureCoupling(K8sStoreConfig.configAreas());
    }

    // ------------------------------------------------------------------ registration gating

    @Test
    void configOnlyAreasRegisterNoOrganizationKinds() {
        K8sStorageBackend backend = start(false, K8sStoreConfig.configAreas());
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.read(OrganizationSpec.class, "master", "org-1"),
                "without the organization area its kinds must not be registered (no informers, no CRDs needed)");
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.read(OrganizationInvitationSpec.class, "master", "some-id"));
    }

    // ------------------------------------------------------------------ writability split

    @Test
    void readOnlyModeRejectsOrganizationsButKeepsInvitationsWritable() {
        start(true, EnumSet.allOf(Area.class));

        OrganizationSpec organization = new OrganizationSpec();
        organization.setId("org-1");
        organization.setRealm("master");
        organization.setName("Acme");
        assertThrows(
                ReadOnlyException.class,
                () -> K8sStorageBackend.update(OrganizationSpec.class, "master", "org-1", organization),
                "organization definitions are configuration: writes must be rejected in read-only mode");

        OrganizationInvitationSpec invitation = new OrganizationInvitationSpec();
        invitation.setId("invitation-1");
        invitation.setRealm("master");
        invitation.setOrganizationId("org-1");
        invitation.setEmail("invitee@acme.example");
        invitation.setCreatedAt(Time.currentTime());
        invitation.setExpiresAt(Time.currentTime() + 3600);
        K8sStorageBackend.update(OrganizationInvitationSpec.class, "master", "invitation-1", invitation);
        assertEquals(
                1,
                client.resources(KeycloakOrganizationInvitationCr.class)
                        .inNamespace("test")
                        .list()
                        .getItems()
                        .size(),
                "invitations are runtime data: writable despite read-only mode");
        K8sStorageBackend.delete(OrganizationInvitationSpec.class, "master", "invitation-1");
        assertTrue(client.resources(KeycloakOrganizationInvitationCr.class)
                .inNamespace("test")
                .list()
                .getItems()
                .isEmpty());
    }

    // ------------------------------------------------------------------ spec round trip

    @Test
    void organizationSpecRoundTripsDomainsAndExcludesEmbeddedCollections() {
        start(false, EnumSet.allOf(Area.class));

        OrganizationSpec spec = new OrganizationSpec();
        spec.setId("org-1");
        spec.setRealm("master");
        spec.setName("Acme");
        spec.setAlias("acme");
        spec.setEnabled(true);
        spec.setDescription("Acme Corp.");
        spec.setRedirectUrl("https://acme.example/after-login");
        spec.setGroupId("org-1");
        spec.setAttributes(Map.of("tier", List.of("gold")));
        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName("acme.example");
        domain.setVerified(true);
        spec.setDomains(Set.of(domain));
        // embedded collections deserialize (for the warn path) but never serialize
        spec.setMembers(List.of(new MemberRepresentation()));
        spec.setGroups(List.of(new GroupRepresentation()));

        K8sStorageBackend.update(OrganizationSpec.class, "master", "org-1", spec);

        OrganizationSpec read = K8sStorageBackend.get().read(OrganizationSpec.class, "master", "org-1");
        assertNotNull(read);
        assertEquals("Acme", read.getName());
        assertEquals("acme", read.getAlias());
        assertTrue(read.isEnabled());
        assertEquals("org-1", read.getGroupId());
        assertEquals(List.of("gold"), read.getAttributes().get("tier"));
        assertNotNull(read.getDomains());
        assertEquals(1, read.getDomains().size());
        OrganizationDomainRepresentation readDomain =
                read.getDomains().iterator().next();
        assertEquals("acme.example", readDomain.getName());
        assertTrue(readDomain.isVerified());
        assertNull(read.getMembers(), "embedded members must not survive the CR round trip");
        assertNull(read.getGroups(), "embedded groups must not survive the CR round trip");
        assertEquals(
                List.of("members", "groups"),
                spec.ignoredEmbeddedCollections(),
                "directly populated embedded collections are detected for the warning");

        KeycloakOrganizationCr cr = new KeycloakOrganizationCr();
        cr.setMetadata(
                new ObjectMetaBuilder().withName("org-1").withNamespace("test").build());
        cr.setSpec(spec);
        String wireJson = K8sStorageBackend.buildSerialization().asJson(cr);
        assertFalse(wireJson.contains(":null"), wireJson);
        assertFalse(wireJson.contains("members"), wireJson);
        assertFalse(wireJson.contains("identityProviders"), wireJson);
        assertTrue(wireJson.contains("acme.example"), wireJson);
    }

    @Test
    void groupSpecRoundTripsOrganizationTypeAndOwner() {
        start(false, EnumSet.allOf(Area.class));

        GroupSpec backing = new GroupSpec();
        backing.setId("org-1");
        backing.setRealm("master");
        backing.setName("org-1");
        backing.setType("organization");
        backing.setOrganizationId("org-1");
        K8sStorageBackend.update(GroupSpec.class, "master", "org-1", backing);

        GroupSpec read = K8sStorageBackend.get().read(GroupSpec.class, "master", "org-1");
        assertNotNull(read);
        assertEquals("organization", read.getType());
        assertEquals("org-1", read.getOrganizationId());

        // realm groups keep their spec shape - no type/organizationId noise in the wire form
        GroupSpec realmGroup = new GroupSpec();
        realmGroup.setId("plain");
        realmGroup.setRealm("master");
        realmGroup.setName("plain");
        KeycloakGroupCr cr = new KeycloakGroupCr();
        cr.setMetadata(
                new ObjectMetaBuilder().withName("plain").withNamespace("test").build());
        cr.setSpec(realmGroup);
        String wireJson = K8sStorageBackend.buildSerialization().asJson(cr);
        assertFalse(wireJson.contains("organizationId"), wireJson);
        assertFalse(wireJson.contains("\"type\""), wireJson);
    }

    @Test
    void handAuthoredOrganizationCrDefaultsIdFromMetadataAndRealmFromLabel() {
        KeycloakOrganizationCr cr = new KeycloakOrganizationCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("acme")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        OrganizationSpec spec = new OrganizationSpec();
        spec.setName("Acme");
        cr.setSpec(spec);
        client.resource(cr).create();

        K8sStorageBackend backend = start(true, EnumSet.allOf(Area.class));

        OrganizationSpec read = backend.read(OrganizationSpec.class, "master", "acme");
        assertNotNull(read, "spec.id must default to metadata.name, the realm to the realm label");
        assertEquals("acme", read.getId());
        assertEquals("Acme", read.getName());
    }
}
