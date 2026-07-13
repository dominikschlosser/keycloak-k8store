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

/**
 * The per-JVM test namespace names. This is the single static exception to supplier-based
 * injection, and it is deliberately name-only: {@link TestNamespaceSupplier} owns the actual
 * namespace lifecycle (creation before the Keycloak server boots, deletion through the
 * framework's close), this class only computes deterministic names.
 *
 * <p>Why a static class at all: {@code KeycloakServerConfig} classes need the namespace name
 * as a server option, but the framework instantiates them without field injection
 * ({@code SupplierHelpers.getInstance}) and calls {@code configure()} during dependency-graph
 * resolution, before any supplier instance is deployed. A supplier therefore cannot hand a
 * value to a server config; sharing pure name constants is the remaining coupling.
 */
public final class TestNamespaces {

    /** Annotation ref of the dynamic-areas namespace, e.g. {@code @InjectTestNamespace(ref = DYNAMIC_REF)}. */
    public static final String DYNAMIC_REF = "dynamic";

    private static final String SUFFIX = Long.toHexString(System.nanoTime());

    private TestNamespaces() {}

    /** Namespace of the config-mode servers; the fixed cluster namespace in remote mode. */
    public static String defaultName() {
        return ServerMode.remote() ? remoteName() : "k8store-test-" + SUFFIX;
    }

    /**
     * Namespace of the dynamic-areas ({@code areas=all}) server - separate from
     * {@link #defaultName()} on purpose. The embedded servers of one JVM share their dev
     * database, and the config-mode servers rely on the bootstrap admin surviving there across
     * restarts; an {@code areas=all} server stores users (incl. the bootstrap admin and the
     * temp-admin service account) as CRs instead. Sharing one namespace would leave whichever
     * mode boots second with a master-realm CR whose admin lives in a store it cannot see. Two
     * namespaces = two independent master-realm bootstraps, each self-contained.
     */
    public static String dynamicName() {
        return ServerMode.remote() ? remoteName() : "k8store-test-dyn-" + SUFFIX;
    }

    static String remoteName() {
        return System.getenv().getOrDefault("K8STORE_NAMESPACE", "keycloak");
    }
}
