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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
import com.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import com.github.dominikschlosser.k8store.tests.config.PartialAreasServerConfig;
import com.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import com.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import com.github.dominikschlosser.k8store.tests.framework.KindCluster;
import com.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;

/**
 * Partial replacement: with {@code group} left out of the areas option, groups are served by the
 * default JPA storage (no CRs), while realms keep coming from custom resources.
 */
@Order(3)
@KeycloakIntegrationTest(config = PartialAreasServerConfig.class)
public class PartialAreasStorageTest {

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @Test
    public void realmsAreCustomResourcesButGroupsStayInDatabase() {
        assertTrue(
                kube.client().resources(KeycloakRealmCr.class).inNamespace(namespace.name()).list().getItems().stream()
                        .anyMatch(cr -> realm.getName().equals(cr.getSpec().getRealm())),
                "realm area is CR-backed");

        GroupRepresentation group = new GroupRepresentation();
        group.setName("jpa-group");
        try (Response response = realm.admin().groups().add(group)) {
            assertEquals(201, response.getStatus());
        }

        assertTrue(
                realm.admin().groups().groups("jpa-group", 0, 10).size() > 0,
                "group is served through the default JPA storage");
        assertTrue(
                kube.client().resources(KeycloakGroupCr.class).inNamespace(namespace.name()).list().getItems().stream()
                        .noneMatch(cr -> "jpa-group".equals(cr.getSpec().getName())),
                "no KeycloakGroup CR may be created for a JPA-backed group");
    }
}
