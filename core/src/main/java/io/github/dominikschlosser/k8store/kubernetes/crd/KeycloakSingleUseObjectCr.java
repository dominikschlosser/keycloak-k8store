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
package io.github.dominikschlosser.k8store.kubernetes.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;
import io.github.dominikschlosser.k8store.crd.SingleUseObjectSpec;

/**
 * A Keycloak single-use object (action token, nonce, code replay guard, ...), stored as a
 * Kubernetes custom resource. Volatile, short-lived runtime state of the experimental dynamic
 * areas - never authored by hand. The spec is the original {@link SingleUseObjectSpec};
 * {@code spec.key} is the store id.
 */
@Group(K8sCrd.GROUP)
@Version(value = K8sCrd.VERSION, served = true, storage = true)
@Kind("KeycloakSingleUseObject")
@Singular("keycloaksingleuseobject")
@Plural("keycloaksingleuseobjects")
@ShortNames("ksuo")
public class KeycloakSingleUseObjectCr extends CustomResource<SingleUseObjectSpec, Void> implements Namespaced {

    /** Explicit factories - the fabric8 default implementations use reflection. */
    @Override
    protected SingleUseObjectSpec initSpec() {
        return new SingleUseObjectSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }
}
