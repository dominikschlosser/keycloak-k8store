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

import com.github.dominikschlosser.k8store.crd.UserSessionSpec;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A Keycloak user session (online or offline, client sessions embedded), stored as a Kubernetes
 * custom resource. Volatile runtime state of the experimental dynamic areas: written by Keycloak
 * on every login/refresh, deleted on logout or expiry - never authored by hand. The spec is the
 * original {@link UserSessionSpec}; {@code spec.id} defaults to {@code metadata.name}.
 */
@Group(K8sCrd.GROUP)
@Version(value = K8sCrd.VERSION, served = true, storage = true)
@Kind("KeycloakUserSession")
@Singular("keycloakusersession")
@Plural("keycloakusersessions")
@ShortNames("kus")
public class KeycloakUserSessionCr extends CustomResource<UserSessionSpec, Void> implements Namespaced {

    /** Explicit factories - the fabric8 default implementations use reflection. */
    @Override
    protected UserSessionSpec initSpec() {
        return new UserSessionSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }
}
