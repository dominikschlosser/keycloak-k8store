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
package com.github.dominikschlosser.k8store.tests.framework;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierOrder;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import org.keycloak.testframework.server.KeycloakServerConfigInterceptor;

/**
 * Supplies the one {@link KindCluster} of the test run: resolves the kubeconfig context
 * ({@code K8STORE_TEST_CONTEXT}, default {@code kind-k8store}), fails fast with an actionable
 * error if the cluster is unreachable, and in embedded mode server-side-applies the committed
 * CRDs and waits until they are established. In remote mode ({@code KC_TEST_SERVER=remote})
 * the CRDs are already applied by the deploy scripts.
 *
 * <p>As a {@link KeycloakServerConfigInterceptor} it points the embedded Keycloak's storage
 * backend at the same kubeconfig context; the always-enabled value type (see
 * {@link K8storeTestFrameworkExtension}) guarantees the cluster is up and the interceptor runs
 * before every server boot, whether or not the test class injects the cluster.
 */
public class KindClusterSupplier
        implements Supplier<KindCluster, InjectKindCluster>,
                KeycloakServerConfigInterceptor<KindCluster, InjectKindCluster> {

    @Override
    public KindCluster getValue(InstanceContext<KindCluster, InjectKindCluster> instanceContext) {
        String contextName = System.getenv().getOrDefault("K8STORE_TEST_CONTEXT", "kind-k8store");
        KubernetesClient client;
        try {
            client = new KubernetesClientBuilder()
                    .withConfig(Config.autoConfigure(contextName))
                    .build();
            client.getKubernetesVersion();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reach the '" + contextName + "' kind cluster - the integration"
                    + " tests run against a real cluster. Create it with scripts/kind-up.sh (or set"
                    + " K8STORE_TEST_CONTEXT to another kubeconfig context).", e);
        }
        if (!ServerMode.remote()) {
            applyCrds(client);
        }
        return new KindCluster(client, contextName);
    }

    @Override
    public boolean compatible(
            InstanceContext<KindCluster, InjectKindCluster> a,
            RequestedInstance<KindCluster, InjectKindCluster> b) {
        return true;
    }

    @Override
    public LifeCycle getDefaultLifecycle() {
        return LifeCycle.GLOBAL;
    }

    @Override
    public void close(InstanceContext<KindCluster, InjectKindCluster> instanceContext) {
        instanceContext.getValue().client().close();
    }

    @Override
    public KeycloakServerConfigBuilder intercept(
            KeycloakServerConfigBuilder serverConfig,
            InstanceContext<KindCluster, InjectKindCluster> instanceContext) {
        if (ServerMode.remote()) {
            return serverConfig;
        }
        return serverConfig.option(
                "spi-datastore--k8store--context", instanceContext.getValue().contextName());
    }

    @Override
    public int order() {
        return SupplierOrder.BEFORE_KEYCLOAK_SERVER;
    }

    /** Server-side-applies the committed CRDs (idempotent) and waits until they are established. */
    private void applyCrds(KubernetesClient client) {
        Path crdDir = Files.isDirectory(Path.of("../crds")) ? Path.of("../crds") : Path.of("crds");
        List<Path> files;
        try (Stream<Path> paths = Files.list(crdDir)) {
            files = paths.filter(f -> f.getFileName().toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list CRD manifests in " + crdDir.toAbsolutePath(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No CRD manifests found in " + crdDir.toAbsolutePath()
                    + " - build them with scripts/update-crds.sh");
        }
        for (Path file : files) {
            CustomResourceDefinition crd;
            try {
                crd = Serialization.unmarshal(Files.readString(file), CustomResourceDefinition.class);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read CRD manifest " + file, e);
            }
            client.resource(crd).fieldManager("k8store-tests").forceConflicts().serverSideApply();
        }
        for (Path file : files) {
            String name = file.getFileName().toString().replace("-v1.yml", "");
            Await.await("CRD " + name + " to become established", () -> {
                CustomResourceDefinition current =
                        client.apiextensions().v1().customResourceDefinitions().withName(name).get();
                return current != null
                        && current.getStatus() != null
                        && current.getStatus().getConditions() != null
                        && current.getStatus().getConditions().stream()
                                .anyMatch(c -> "Established".equals(c.getType()) && "True".equals(c.getStatus()));
            });
        }
    }
}
