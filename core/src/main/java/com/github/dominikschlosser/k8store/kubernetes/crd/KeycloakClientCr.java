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

import com.github.dominikschlosser.k8store.crd.ClientSpec;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A Keycloak client, stored as a Kubernetes custom resource. The spec is Keycloak's client
 * representation ({@link ClientSpec}); {@code spec.clientId} is the store id and defaults to
 * {@code metadata.name}, {@code spec.realm} is required (or supplied via the realm label).
 */
@Group(KeycloakClientCr.GROUP)
@Version(value = KeycloakClientCr.VERSION, served = true, storage = true)
@Kind("KeycloakClient")
@Singular("keycloakclient")
@Plural("keycloakclients")
@ShortNames("kc")
public class KeycloakClientCr extends CustomResource<ClientSpec, Void> implements Namespaced {

    /** Explicit factories — the fabric8 default implementations use reflection. */
    @Override
    protected ClientSpec initSpec() {
        return new ClientSpec();
    }

    @Override
    protected Void initStatus() {
        return null;
    }

    public static final String GROUP = "k8store.dominikschlosser.github.io";
    public static final String VERSION = "v1alpha1";
}
