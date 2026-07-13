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
package io.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import io.github.dominikschlosser.k8store.tests.config.K8StoreServerConfig;
import io.github.dominikschlosser.k8store.tests.framework.Await;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.remote.runonserver.InjectRunOnServer;
import org.keycloak.testframework.remote.runonserver.RunOnServerClient;

/**
 * Proves the CRs are the actual storage - not a write-through veneer over the JPA database that
 * happens to also exist (dev-mem H2 is configured for users/sessions, so Keycloak COULD silently
 * serve config entities from it if the datastore wiring were broken).
 *
 * <p>Two independent angles:
 *
 * <ol>
 *   <li>Out-of-band CR mutations (which the database cannot know about) must change what the
 *       admin API returns, and out-of-band CR deletion must make entities vanish.
 *   <li>Inside the server, the JPA config tables must stay empty while the model API serves
 *       realms/clients - asserted with native queries through the server's own entity manager.
 * </ol>
 */
@Order(1)
@KeycloakIntegrationTest(config = K8StoreServerConfig.class)
public class CrStoreIsAuthoritativeTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectRunOnServer
    RunOnServerClient runOnServer;

    @Test
    public void clientReadsFollowOutOfBandCrChanges() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("authority-client");
        client.setEnabled(true);
        client.setDescription("original");
        try (Response response = realm.admin().clients().create(client)) {
            assertEquals(201, response.getStatus());
        }

        KeycloakClientCr cr =
                kube.client().resources(KeycloakClientCr.class).inNamespace(namespace.name()).list().getItems().stream()
                        .filter(c -> "authority-client".equals(c.getSpec().getClientId())
                                && realm.getName().equals(c.getSpec().getRealm()))
                        .findFirst()
                        .orElseThrow();

        // mutate the CR the way a GitOps pipeline would - the database knows nothing about this
        cr.getSpec().setDescription("changed-out-of-band");
        kube.client().resource(cr).update();

        Await.await("admin API to serve the out-of-band client change", () -> "changed-out-of-band"
                .equals(realm.admin()
                        .clients()
                        .findByClientId("authority-client")
                        .get(0)
                        .getDescription()));

        // out-of-band deletion must make the client vanish from Keycloak
        kube.client().resource(cr).delete();
        Await.await(
                "admin API to stop serving the deleted client",
                () -> realm.admin().clients().findByClientId("authority-client").isEmpty());
    }

    @Test
    public void roleReadsFollowOutOfBandCrChanges() {
        RoleRepresentation role = new RoleRepresentation();
        role.setName("authority-role");
        role.setDescription("original");
        realm.admin().roles().create(role);

        KeycloakRoleCr cr =
                kube.client().resources(KeycloakRoleCr.class).inNamespace(namespace.name()).list().getItems().stream()
                        .filter(r -> "authority-role".equals(r.getSpec().getName())
                                && realm.getName().equals(r.getSpec().getRealm()))
                        .findFirst()
                        .orElseThrow();

        cr.getSpec().setDescription("changed-out-of-band");
        kube.client().resource(cr).update();

        Await.await("admin API to serve the out-of-band role change", () -> "changed-out-of-band"
                .equals(realm.admin()
                        .roles()
                        .get("authority-role")
                        .toRepresentation()
                        .getDescription()));
    }

    @Test
    public void realmReadsFollowOutOfBandCrChanges() {
        KeycloakRealmCr cr =
                kube.client().resources(KeycloakRealmCr.class).inNamespace(namespace.name()).list().getItems().stream()
                        .filter(r -> realm.getName().equals(r.getSpec().getRealm()))
                        .findFirst()
                        .orElseThrow();

        cr.getSpec().setDisplayName("write-mode-out-of-band");
        kube.client().resource(cr).update();

        Await.await("admin API to serve the out-of-band realm change", () -> "write-mode-out-of-band"
                .equals(realm.admin().toRepresentation().getDisplayName()));
    }

    @Test
    public void componentConfigUpdatePersistsToRealmCr() {
        // the declarative-user-profile component receives its config via a component UPDATE
        // after creation - the exact path that used to lose nested-entity mutations
        UPConfig upConfig = realm.admin().users().userProfile().getConfiguration();
        upConfig.setUnmanagedAttributePolicy(UnmanagedAttributePolicy.ENABLED);
        realm.admin().users().userProfile().update(upConfig);

        Await.await(
                "user-profile component config to land in the realm CR",
                () ->
                        kube
                                .client()
                                .resources(KeycloakRealmCr.class)
                                .inNamespace(namespace.name())
                                .list()
                                .getItems()
                                .stream()
                                .filter(r -> realm.getName().equals(r.getSpec().getRealm()))
                                .findFirst()
                                .map(KeycloakRealmCr::getSpec)
                                .map(spec -> spec.getComponents().values().stream()
                                        .flatMap(List::stream)
                                        .filter(c -> "declarative-user-profile".equals(c.getProviderId()))
                                        .anyMatch(c -> c.getConfig() != null
                                                && c.getConfig().get("kc.user.profile.config") != null
                                                && c.getConfig()
                                                        .get("kc.user.profile.config")
                                                        .toString()
                                                        .contains("ENABLED")))
                                .orElse(false));
    }

    @Test
    public void protocolMapperUpdatePersistsToClientCr() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("mapper-client");
        client.setEnabled(true);
        try (Response response = realm.admin().clients().create(client)) {
            assertEquals(201, response.getStatus());
        }
        String id =
                realm.admin().clients().findByClientId("mapper-client").get(0).getId();

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("hardcoded");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        mapper.setConfig(new HashMap<>(Map.of("claim.name", "team", "claim.value", "old")));
        try (Response response =
                realm.admin().clients().get(id).getProtocolMappers().createMapper(mapper)) {
            assertEquals(201, response.getStatus());
        }

        ProtocolMapperRepresentation created =
                realm.admin().clients().get(id).getProtocolMappers().getMappers().stream()
                        .filter(m -> "hardcoded".equals(m.getName()))
                        .findFirst()
                        .orElseThrow();
        created.getConfig().put("claim.value", "updated-value");
        realm.admin().clients().get(id).getProtocolMappers().update(created.getId(), created);

        Await.await(
                "protocol mapper update to land in the client CR",
                () ->
                        kube
                                .client()
                                .resources(KeycloakClientCr.class)
                                .inNamespace(namespace.name())
                                .list()
                                .getItems()
                                .stream()
                                .filter(c -> "mapper-client".equals(c.getSpec().getClientId()))
                                .findFirst()
                                .map(c -> c.getSpec().getProtocolMappers().stream()
                                        .anyMatch(m -> "hardcoded".equals(m.getName())
                                                && "updated-value"
                                                        .equals(m.getConfig().get("claim.value"))))
                                .orElse(false));
    }

    @Test
    public void jpaConfigTablesStayEmptyWhileModelServesData() {
        String realmName = realm.getName();
        String report = runOnServer.fetchString(session -> {
            var em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
            long realmRows =
                    ((Number) em.createNativeQuery("select count(*) from REALM").getSingleResult()).longValue();
            long clientRows = ((Number)
                            em.createNativeQuery("select count(*) from CLIENT").getSingleResult())
                    .longValue();
            long roleRows = ((Number) em.createNativeQuery("select count(*) from KEYCLOAK_ROLE")
                            .getSingleResult())
                    .longValue();
            long groupRows = ((Number) em.createNativeQuery("select count(*) from KEYCLOAK_GROUP")
                            .getSingleResult())
                    .longValue();
            long scopeRows = ((Number) em.createNativeQuery("select count(*) from CLIENT_SCOPE")
                            .getSingleResult())
                    .longValue();
            long modelRealms = session.realms().getRealmsStream().count();
            boolean managedRealmServed = session.realms().getRealmByName(realmName) != null;
            return "realmRows=" + realmRows + " clientRows=" + clientRows + " roleRows=" + roleRows
                    + " groupRows=" + groupRows + " scopeRows=" + scopeRows
                    + " modelRealms=" + modelRealms + " managedRealmServed=" + managedRealmServed;
        });

        assertTrue(report.contains("realmRows=0"), report);
        assertTrue(report.contains("clientRows=0"), report);
        assertTrue(report.contains("roleRows=0"), report);
        assertTrue(report.contains("groupRows=0"), report);
        assertTrue(report.contains("scopeRows=0"), report);
        assertTrue(report.contains("managedRealmServed=true"), report);
        // at least master + the managed realm are served purely from CRs
        assertTrue(report.matches(".*modelRealms=([2-9]|\\d{2,}).*"), report);
    }
}
