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
package io.github.dominikschlosser.k8store.tests.framework;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierOrder;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import org.keycloak.testframework.server.KeycloakServerConfigInterceptor;

/**
 * Supplies the one {@link KindCluster} of the test run: resolves the kubeconfig context
 * ({@code K8STORE_TEST_CONTEXT}, default {@code kind-k8store}) and, in embedded mode,
 * server-side-applies the committed CRDs and waits until they are established. In remote mode
 * ({@code KC_TEST_SERVER=remote}) the CRDs are already applied by the deploy scripts.
 *
 * <p>Cluster lifecycle: an already-running cluster on the context is reused; if none is reachable
 * in embedded mode, one is created on the fly ({@code kind create cluster}) so {@code mvn verify}
 * needs no manual setup. The created cluster is left running and reused by later runs (remove it
 * with scripts/kind-down.sh). Remote mode, and a non-{@code kind-} context, fail with guidance
 * instead of auto-creating, since those are clusters this run does not own.
 *
 * <p>Why provision through the {@code kind} CLI (a {@link ProcessBuilder}) rather than a
 * testcontainers library ({@code kindcontainer} / testcontainers-k3s): the suite needs a real
 * <b>multi-node</b> cluster - the e2e tier runs two Keycloak replicas pinned to separate worker
 * nodes to surface the cross-replica informer-consistency behaviour the store depends on, which a
 * single-node in-container API server cannot reproduce. kind builds that topology and has no JVM
 * binding, so the CLI is the only way to drive it from Java; the embedded auto-create uses the
 * same kind (same topology) to keep one cluster technology across both tiers. The library route's
 * other differences (Docker-only, no reuse, auto-teardown) are not what drove the choice.
 *
 * <p>As a {@link KeycloakServerConfigInterceptor} it points the embedded Keycloak's storage
 * backend at the same kubeconfig context; the always-enabled value type (see
 * {@link K8storeTestFrameworkExtension}) guarantees the cluster is up and the interceptor runs
 * before every server boot, whether or not the test class injects the cluster.
 */
public class KindClusterSupplier
        implements Supplier<KindCluster, InjectKindCluster>,
                KeycloakServerConfigInterceptor<KindCluster, InjectKindCluster> {

    private static final Logger LOG = Logger.getLogger(KindClusterSupplier.class);
    private static final String KIND_CONTEXT_PREFIX = "kind-";

    /** 1 control-plane + 2 workers, mirroring scripts/kind-up.sh (the multi-node topology). */
    private static final String KIND_CONFIG = "kind: Cluster\n"
            + "apiVersion: kind.x-k8s.io/v1alpha4\n"
            + "nodes:\n"
            + "  - role: control-plane\n"
            + "  - role: worker\n"
            + "  - role: worker\n";

    @Override
    public KindCluster getValue(InstanceContext<KindCluster, InjectKindCluster> instanceContext) {
        String contextName = System.getenv().getOrDefault("K8STORE_TEST_CONTEXT", "kind-k8store");
        KubernetesClient client = tryConnect(contextName);
        if (client == null) {
            // No reachable cluster. In embedded mode create one on the fly so `mvn verify` needs no
            // manual setup; an already-running cluster is detected above and reused. Remote mode
            // expects Keycloak deployed in the cluster, and a non-kind context is someone else's
            // cluster, so both fail with guidance rather than auto-create.
            if (ServerMode.remote() || !contextName.startsWith(KIND_CONTEXT_PREFIX)) {
                throw new IllegalStateException("Cannot reach the '" + contextName + "' cluster. In remote mode"
                        + " deploy it first (scripts/deploy.sh); otherwise point K8STORE_TEST_CONTEXT at a"
                        + " running cluster.");
            }
            createKindCluster(contextName.substring(KIND_CONTEXT_PREFIX.length()));
            client = tryConnect(contextName);
            if (client == null) {
                throw new IllegalStateException(
                        "Created the kind cluster but still cannot reach context '" + contextName + "'.");
            }
        }
        if (!ServerMode.remote()) {
            applyCrds(client);
        }
        return new KindCluster(client, contextName);
    }

    /** Connects to the context and probes reachability; returns null when no cluster answers. */
    private KubernetesClient tryConnect(String contextName) {
        try {
            KubernetesClient client = new KubernetesClientBuilder()
                    .withConfig(Config.autoConfigure(contextName))
                    .build();
            client.getKubernetesVersion();
            return client;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates the kind cluster of the given name (kind names its context {@code kind-<name>}) with
     * one control-plane and two worker nodes - the same multi-node topology scripts/kind-up.sh
     * builds, and the reason this provisions through the kind CLI rather than a testcontainers
     * library (see the class javadoc). Waits until the control plane is ready. The cluster is left
     * running and reused by later runs; remove it with scripts/kind-down.sh. Requires the
     * {@code kind} binary and Docker.
     */
    private void createKindCluster(String clusterName) {
        LOG.infof(
                "No reachable kind cluster; creating a multi-node '%s' (1 control-plane + 2 workers)."
                        + " It is left running for later runs (remove with scripts/kind-down.sh).",
                clusterName);
        ProcessBuilder command = new ProcessBuilder(
                        "kind", "create", "cluster", "--name", clusterName, "--config=-", "--wait", "120s")
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = command.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(KIND_CONFIG.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out creating the kind cluster '" + clusterName + "'.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("'kind create cluster --name " + clusterName + "' failed with exit"
                        + " code " + process.exitValue() + " (is Docker running?).");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not run 'kind' to create the test cluster - install kind"
                            + " (https://kind.sigs.k8s.io) and Docker, or create the cluster with scripts/kind-up.sh.",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the kind cluster '" + clusterName + "'.", e);
        }
    }

    @Override
    public boolean compatible(
            InstanceContext<KindCluster, InjectKindCluster> a, RequestedInstance<KindCluster, InjectKindCluster> b) {
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
            KeycloakServerConfigBuilder serverConfig, InstanceContext<KindCluster, InjectKindCluster> instanceContext) {
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
            files = paths.filter(f -> f.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
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
                CustomResourceDefinition current = client.apiextensions()
                        .v1()
                        .customResourceDefinitions()
                        .withName(name)
                        .get();
                return current != null
                        && current.getStatus() != null
                        && current.getStatus().getConditions() != null
                        && current.getStatus().getConditions().stream()
                                .anyMatch(c -> "Established".equals(c.getType()) && "True".equals(c.getStatus()));
            });
        }
    }
}
