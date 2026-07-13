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
 * A Kubernetes namespace the Keycloak server under test stores its custom resources in.
 * Inject with {@link InjectTestNamespace}; supplied by {@link TestNamespaceSupplier}.
 */
public final class TestNamespace {

    private final String name;
    // captured so close() can delete the namespace even while the registry tears down the
    // cluster instance (the dependency lookup is unavailable during a cascading destroy)
    private final KindCluster cluster;

    TestNamespace(String name, KindCluster cluster) {
        this.name = name;
        this.cluster = cluster;
    }

    public String name() {
        return name;
    }

    KindCluster cluster() {
        return cluster;
    }

    @Override
    public String toString() {
        return name;
    }
}
