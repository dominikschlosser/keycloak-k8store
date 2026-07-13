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

import java.util.List;
import java.util.Map;
import org.keycloak.testframework.TestFrameworkExtension;
import org.keycloak.testframework.injection.Supplier;

/**
 * Keycloak test framework extension of the k8store integration tests, registered through
 * {@code META-INF/services/org.keycloak.testframework.TestFrameworkExtension}.
 *
 * <p>Both value types are always enabled: every Keycloak server the framework boots needs the
 * kind cluster reachable, the CRDs established and the default test namespace present, even
 * when a test class injects neither.
 */
public class K8storeTestFrameworkExtension implements TestFrameworkExtension {

    @Override
    public List<Supplier<?, ?>> suppliers() {
        return List.of(new KindClusterSupplier(), new TestNamespaceSupplier());
    }

    @Override
    public List<Class<?>> alwaysEnabledValueTypes() {
        return List.of(KindCluster.class, TestNamespace.class);
    }

    @Override
    public Map<Class<?>, String> valueTypeAliases() {
        return Map.of(
                KindCluster.class, "kind-cluster",
                TestNamespace.class, "test-namespace");
    }
}
