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

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Access to the real Kubernetes cluster behind the Keycloak server under test — the integration
 * tests run against a kind cluster, never a mock.
 *
 * <p>Embedded mode (default): the embedded Keycloak's storage backend is pointed at the
 * {@code kind-k8store} kubeconfig context (override with {@code K8STORE_TEST_CONTEXT}) and an
 * ephemeral, per-JVM test namespace; the CRDs from {@code crds/} are applied on first use.
 * Create the cluster with {@code scripts/kind-up.sh}.
 *
 * <p>Remote mode ({@code KC_TEST_SERVER=remote}, see scripts/e2e.sh): the tests talk to the
 * Keycloak deployment in the cluster and to its {@code keycloak} namespace.
 */
public final class TestKube {

    private static final String CONTEXT = System.getenv().getOrDefault("K8STORE_TEST_CONTEXT", "kind-k8store");

    private static KubernetesClient client;
    private static String testNamespace;
    private static String dynamicTestNamespace;
    private static boolean crdsApplied;

    private TestKube() {}

    public static boolean isRemote() {
        String mode = System.getProperty("kc.test.server", System.getenv("KC_TEST_SERVER"));
        return "remote".equals(mode);
    }

    /** The kubeconfig context the tests (and the embedded server's backend) connect with. */
    public static String contextName() {
        return CONTEXT;
    }

    public static synchronized KubernetesClient client() {
        if (client == null) {
            KubernetesClient created;
            try {
                created = new KubernetesClientBuilder()
                        .withConfig(Config.autoConfigure(CONTEXT))
                        .build();
                created.getKubernetesVersion();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot reach the '" + CONTEXT + "' kind cluster — the integration"
                        + " tests run against a real cluster. Create it with scripts/kind-up.sh (or set"
                        + " K8STORE_TEST_CONTEXT to another kubeconfig context).", e);
            }
            client = created;
        }
        return client;
    }

    public static synchronized String namespace() {
        if (isRemote()) {
            return System.getenv().getOrDefault("K8STORE_NAMESPACE", "keycloak");
        }
        if (testNamespace == null) {
            testNamespace = createTestNamespace("k8store-test-" + Long.toHexString(System.nanoTime()));
        }
        return testNamespace;
    }

    /**
     * Namespace of the dynamic-areas ({@code areas=all}) server — separate from
     * {@link #namespace()} on purpose. The embedded servers of one JVM share their dev database,
     * and the config-mode servers rely on the bootstrap admin surviving there across restarts;
     * an {@code areas=all} server stores users (incl. the bootstrap admin and the temp-admin
     * service account) as CRs instead. Sharing one namespace would leave whichever mode boots
     * second with a master-realm CR whose admin lives in a store it cannot see. Two namespaces
     * = two independent master-realm bootstraps, each self-contained.
     */
    public static synchronized String dynamicNamespace() {
        if (isRemote()) {
            return System.getenv().getOrDefault("K8STORE_NAMESPACE", "keycloak");
        }
        if (dynamicTestNamespace == null) {
            dynamicTestNamespace = createTestNamespace("k8store-test-dyn-" + Long.toHexString(System.nanoTime()));
        }
        return dynamicTestNamespace;
    }

    private static String createTestNamespace(String name) {
        applyCrds();
        client().namespaces()
                .resource(new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build())
                .create();
        if (System.getenv("K8STORE_TEST_KEEP_NAMESPACE") == null) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> client().namespaces().withName(name).delete(), "k8store-test-ns-cleanup"));
        }
        return name;
    }

    /** Ensures cluster, CRDs and the test namespace exist before the Keycloak server boots. */
    public static void ensureAvailable() {
        if (!isRemote()) {
            namespace();
        }
    }

    /** Server-side-applies the committed CRDs (idempotent) and waits until they are established. */
    private static void applyCrds() {
        if (crdsApplied) {
            return;
        }
        Path crdDir = Files.isDirectory(Path.of("../crds")) ? Path.of("../crds") : Path.of("crds");
        List<Path> files;
        try (Stream<Path> paths = Files.list(crdDir)) {
            files = paths.filter(f -> f.getFileName().toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list CRD manifests in " + crdDir.toAbsolutePath(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No CRD manifests found in " + crdDir.toAbsolutePath()
                    + " — build them with scripts/update-crds.sh");
        }
        for (Path file : files) {
            CustomResourceDefinition crd;
            try {
                crd = Serialization.unmarshal(Files.readString(file), CustomResourceDefinition.class);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read CRD manifest " + file, e);
            }
            client().resource(crd).fieldManager("k8store-tests").forceConflicts().serverSideApply();
        }
        for (Path file : files) {
            String name = file.getFileName().toString().replace("-v1.yml", "");
            await("CRD " + name + " to become established", () -> {
                CustomResourceDefinition current =
                        client().apiextensions().v1().customResourceDefinitions().withName(name).get();
                return current != null
                        && current.getStatus() != null
                        && current.getStatus().getConditions() != null
                        && current.getStatus().getConditions().stream()
                                .anyMatch(c -> "Established".equals(c.getType()) && "True".equals(c.getStatus()));
            });
        }
        crdsApplied = true;
    }

    public static void await(String message, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for: " + message);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
