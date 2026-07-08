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
package com.github.dominikschlosser.k8store.kubernetes.crd;

import com.github.dominikschlosser.k8store.crd.RoleSpec;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A Keycloak realm or client role, stored as a Kubernetes custom resource. The spec is
 * Keycloak's role representation ({@link RoleSpec}); {@code spec.id} defaults to
 * {@code metadata.name}, {@code spec.realm} is required (or supplied via the realm label).
 */
@Group(K8sCrd.GROUP)
@Version(value = K8sCrd.VERSION, served = true, storage = true)
@Kind("KeycloakRole")
@Singular("keycloakrole")
@Plural("keycloakroles")
@ShortNames("kro")
public class KeycloakRoleCr extends CustomResource<RoleSpec, Void> implements Namespaced {

    /** Explicit factories - the fabric8 default implementations use reflection. */
    @Override
    protected RoleSpec initSpec() {
        return new RoleSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }
}
