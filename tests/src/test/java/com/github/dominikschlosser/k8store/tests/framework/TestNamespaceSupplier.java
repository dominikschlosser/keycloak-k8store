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

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import java.util.List;
import java.util.Objects;
import org.keycloak.testframework.injection.DependenciesBuilder;
import org.keycloak.testframework.injection.Dependency;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierOrder;

/**
 * Supplies the ephemeral {@code k8store-test-*} namespaces (names from
 * {@link TestNamespaces}). The default ref is created once per JVM (always enabled, so it
 * exists before the first Keycloak server boots); the {@code dynamic} ref is created when a
 * test class of the {@code areas=all} server first injects it. Both are GLOBAL and deleted
 * through the framework's close at the end of the run, unless
 * {@code K8STORE_TEST_KEEP_NAMESPACE} is set. In remote mode the supplier returns the fixed
 * cluster namespace and never creates or deletes anything.
 */
public class TestNamespaceSupplier implements Supplier<TestNamespace, InjectTestNamespace> {

    @Override
    public List<Dependency> getDependencies(RequestedInstance<TestNamespace, InjectTestNamespace> instanceContext) {
        return DependenciesBuilder.create(KindCluster.class).build();
    }

    @Override
    public TestNamespace getValue(InstanceContext<TestNamespace, InjectTestNamespace> instanceContext) {
        KindCluster cluster = instanceContext.getDependency(KindCluster.class);
        if (ServerMode.remote()) {
            return new TestNamespace(TestNamespaces.remoteName(), cluster);
        }
        String name = nameForRef(instanceContext.getRef());
        cluster.client()
                .namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .endMetadata()
                        .build())
                .create();
        return new TestNamespace(name, cluster);
    }

    private String nameForRef(String ref) {
        if (ref == null) {
            return TestNamespaces.defaultName();
        }
        if (TestNamespaces.DYNAMIC_REF.equals(ref)) {
            return TestNamespaces.dynamicName();
        }
        throw new IllegalArgumentException(
                "Unknown test namespace ref '" + ref + "' - use the default ref" + " or TestNamespaces.DYNAMIC_REF");
    }

    @Override
    public boolean compatible(
            InstanceContext<TestNamespace, InjectTestNamespace> a,
            RequestedInstance<TestNamespace, InjectTestNamespace> b) {
        return Objects.equals(a.getRef(), b.getRef());
    }

    @Override
    public LifeCycle getDefaultLifecycle() {
        return LifeCycle.GLOBAL;
    }

    @Override
    public void close(InstanceContext<TestNamespace, InjectTestNamespace> instanceContext) {
        if (ServerMode.remote() || System.getenv("K8STORE_TEST_KEEP_NAMESPACE") != null) {
            return;
        }
        TestNamespace namespace = instanceContext.getValue();
        namespace.cluster().client().namespaces().withName(namespace.name()).delete();
    }

    @Override
    public int order() {
        return SupplierOrder.BEFORE_KEYCLOAK_SERVER;
    }
}
