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

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * The real Kubernetes cluster behind the Keycloak server under test - the integration tests run
 * against a kind cluster, never a mock. Inject with {@link InjectKindCluster}; supplied by
 * {@link KindClusterSupplier}, which owns kubeconfig-context resolution, the reachability check
 * and CRD application.
 */
public final class KindCluster {

    private final KubernetesClient client;
    private final String contextName;

    KindCluster(KubernetesClient client, String contextName) {
        this.client = client;
        this.contextName = contextName;
    }

    /** Client connected to the cluster the Keycloak server's storage backend uses. */
    public KubernetesClient client() {
        return client;
    }

    /** The kubeconfig context the tests (and the embedded server's backend) connect with. */
    public String contextName() {
        return contextName;
    }
}
